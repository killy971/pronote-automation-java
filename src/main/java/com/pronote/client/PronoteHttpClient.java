package com.pronote.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.auth.CryptoHelper;
import com.pronote.auth.PronoteSession;
import com.pronote.safety.RateLimiter;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Low-level HTTP client for the Pronote encrypted JSON-RPC protocol.
 *
 * <p>Handles:
 * <ul>
 *   <li>AES encryption/decryption of request and response envelopes</li>
 *   <li>Order counter management</li>
 *   <li>Session cookie injection</li>
 *   <li>Rate limiting (delegates to {@link RateLimiter})</li>
 * </ul>
 *
 * <p>All data scrapers go through {@link #encryptedPost}.
 */
public class PronoteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PronoteHttpClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper jackson;
    private final RateLimiter rateLimiter;

    public PronoteHttpClient(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.jackson = new ObjectMapper();
    }

    /**
     * Sends an AES-encrypted JSON-RPC POST request.
     *
     * @param session      active Pronote session
     * @param function     which API function to call
     * @param params       the inner {@code donnees} object (will be AES-encrypted)
     * @return decrypted inner response node (typically the {@code donnees} object)
     * @throws PronoteClientException on any HTTP or protocol error
     */
    public JsonNode encryptedPost(PronoteSession session, ApiFunction function, ObjectNode params) {
        return encryptedPost(session, function, params, 0);
    }

    /**
     * Sends a JSON-RPC POST with an optional onglet Signature.
     * Pass {@code onglet > 0} to include {@code {"Signature": {"onglet": N}}} in dataSec,
     * as required by post-login API functions (e.g. ListeTravailAFaire=88, PageEmploiDuTemps=16).
     */
    public JsonNode encryptedPost(PronoteSession session, ApiFunction function, ObjectNode params, int onglet) {
        rateLimiter.await();

        int order = session.nextOrder();
        String url = session.getBaseUrl() + "appelfonction/" + session.getAppId()
                + "/" + session.getSessionHandle() + "/" + order;

        // Encrypt order counter
        String orderHex = CryptoHelper.toHex(
                CryptoHelper.aesEncrypt(
                        String.valueOf(order).getBytes(StandardCharsets.UTF_8),
                        session.getAesKey(), session.getAesIv()));

        // Build dataSec as plain JSON object (CrA=false mode — matches pronotepy default)
        ObjectNode dataSecNode = jackson.createObjectNode();
        if (onglet > 0) {
            ObjectNode sig = jackson.createObjectNode().put("onglet", onglet);
            // Parent accounts must identify the target child in every Signature
            if (session.getChildId() != null) {
                sig.set("membre", jackson.createObjectNode()
                        .put("N", session.getChildId())
                        .put("G", 4));
            }
            dataSecNode.set("Signature", sig);
        }
        dataSecNode.set("data", params);

        // Outer envelope
        ObjectNode envelope = jackson.createObjectNode();
        envelope.put("session", session.getSessionHandle());
        envelope.put("no", orderHex);
        envelope.put("id", function.getValue());
        envelope.set("dataSec", dataSecNode);

        String envelopeJson;
        try {
            envelopeJson = jackson.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new PronoteClientException("Failed to serialize envelope: " + e.getMessage(), e);
        }

        // Build HTTP request with session cookies
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(envelopeJson, JSON));
        for (Map.Entry<String, String> cookie : session.getCookies().entrySet()) {
            reqBuilder.addHeader("Cookie", cookie.getKey() + "=" + cookie.getValue());
        }

        log.debug("→ POST {} [{}] order={}", function.getValue(), url, order);

        try (Response response = http.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new PronoteClientException("HTTP " + response.code() + " calling " + function.getValue());
            }
            ResponseBody body = response.body();
            if (body == null) throw new PronoteClientException("Empty response from " + function.getValue());
            String responseJson = body.string();
            log.debug("← HTTP 200 ({} bytes)", responseJson.length());

            JsonNode outer;
            try {
                outer = jackson.readTree(responseJson);
            } catch (Exception e) {
                throw new PronoteClientException("Invalid JSON response from " + function.getValue(), e);
            }

            // Surface server errors
            if (outer.has("Erreur")) {
                JsonNode err = outer.get("Erreur");
                String msg = err.has("Titre") ? err.get("Titre").asText() : err.asText();
                throw new PronoteClientException(function.getValue() + " error from server: " + msg);
            }

            // Navigate to data: outer → dataSec → data (CrA=false plain JSON mode)
            JsonNode responseDataSec = outer.get("dataSec");
            if (responseDataSec == null) {
                return outer;
            }
            if (responseDataSec.isTextual()) {
                // CrA=true: dataSec is AES-encrypted hex — decrypt and parse
                byte[] decrypted = CryptoHelper.aesDecrypt(
                        CryptoHelper.fromHex(responseDataSec.asText()),
                        session.getAesKey(), session.getAesIv());
                try {
                    JsonNode decryptedNode = jackson.readTree(new String(decrypted, StandardCharsets.UTF_8));
                    return decryptedNode.has("data") ? decryptedNode.get("data") : decryptedNode;
                } catch (Exception e) {
                    throw new PronoteClientException("Failed to parse decrypted dataSec from " + function.getValue(), e);
                }
            }
            // CrA=false: dataSec is a plain JSON object — navigate to its data field
            JsonNode data = responseDataSec.get("data");
            return data != null ? data : responseDataSec;

        } catch (IOException e) {
            throw new PronoteClientException("Network error calling " + function.getValue() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Downloads a file from {@code url} to {@code target}, respecting rate limiting.
     *
     * <p>Session cookies are injected so Pronote-hosted files (G=1) are accessible.
     * The parent directory of {@code target} is created if absent.
     *
     * @param url     the URL to fetch (may be session-scoped for Pronote G=1 files)
     * @param cookies session cookies to inject (use {@code session.getCookies()})
     * @param target  destination file path
     * @return MIME type from Content-Type header, or null if not present
     * @throws PronoteClientException on HTTP error or I/O failure
     */
    public String download(String url, Map<String, String> cookies, Path target) {
        rateLimiter.await();
        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            reqBuilder.addHeader("Cookie", cookie.getKey() + "=" + cookie.getValue());
        }
        log.debug("→ GET (attachment) {}", url);
        try (Response response = http.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new PronoteClientException("HTTP " + response.code() + " downloading " + url);
            }
            ResponseBody body = response.body();
            if (body == null) throw new PronoteClientException("Empty response downloading " + url);

            String mimeType = null;
            MediaType ct = body.contentType();
            if (ct != null) mimeType = ct.type() + "/" + ct.subtype();

            Files.createDirectories(target.getParent());
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            log.debug("← Downloaded {} bytes to {}", Files.size(target), target.getFileName());
            return mimeType;
        } catch (IOException e) {
            throw new PronoteClientException("I/O error downloading " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Performs a lightweight probe call to verify the session is still alive.
     *
     * @return true if the session is valid
     */
    public boolean probe(PronoteSession session) {
        try {
            ObjectNode params = jackson.createObjectNode();
            encryptedPost(session, ApiFunction.FONCTION_PARAMETRES, params);
            return true;
        } catch (Exception e) {
            log.debug("Session probe failed: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------

    public static class PronoteClientException extends RuntimeException {
        public PronoteClientException(String message) { super(message); }
        public PronoteClientException(String message, Throwable cause) { super(message, cause); }
    }
}
