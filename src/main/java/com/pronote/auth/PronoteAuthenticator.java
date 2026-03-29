package com.pronote.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pronote.config.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements the full Pronote authentication flow against the parent (or student) portal.
 *
 * <p>Protocol derived from pronotepy (https://github.com/bain3/pronotepy):
 * <ol>
 *   <li>GET parent.html?fd=1 — extract session handle (h), app id (a), and flags</li>
 *   <li>Generate random 16-byte temporary IV; RSA-encrypt if http=true, else use raw</li>
 *   <li>POST FonctionParametres — dataSec is plain JSON when CrA=false</li>
 *   <li>Update session IV to MD5(ivTemp)</li>
 *   <li>POST Identification — get challenge and alea</li>
 *   <li>Derive auth key: MD5(username + SHA256(alea+password).hexUpperCase)</li>
 *   <li>Decrypt challenge, apply _enleverAlea (keep even-indexed chars), re-encrypt</li>
 *   <li>POST Authentification — send solved challenge</li>
 *   <li>Re-derive session key from decrypted cle field</li>
 * </ol>
 */
public class PronoteAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(PronoteAuthenticator.class);

    // Pattern to match h and a from: Start ({h:12345,a:3,...}) or Start({h:'12345',a:'3',...})
    private static final Pattern H_PATTERN = Pattern.compile("\"?h\"?\\s*:\\s*'?(\\d+)'?");
    private static final Pattern A_PATTERN = Pattern.compile("[{,]\"?a\"?\\s*:\\s*'?(\\d+)'?");

    private final OkHttpClient http;
    private final ObjectMapper jackson;
    private Map<String, String> lastFetchedCookies = new HashMap<>();

    public PronoteAuthenticator() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .build();
        this.jackson = new ObjectMapper();
    }

    public PronoteSession login(AppConfig config) {
        String baseUrl = normalizeBaseUrl(config.getPronote().getBaseUrl());
        String loginPage = baseUrl + config.getPronote().getLoginMode().name().toLowerCase() + ".html?fd=1";

        log.info("Starting Pronote login (mode={}, url={})",
                config.getPronote().getLoginMode(), loginPage);

        // ---- Step 1: GET the login page -----------------------------------
        String html = fetchHtml(loginPage);
        log.debug("Login page fetched ({} chars)", html.length());

        if (!html.contains("loginInfos") && !html.contains("Start (")) {
            throw new AuthException("Login page does not appear to be a direct Pronote portal. "
                    + "The instance may require ENT/SSO authentication, which is not supported.");
        }

        // ---- Step 2: Parse session parameters from Start({h:...,a:...,...}) -
        int h = extractIntParam(html, H_PATTERN, "session handle (h)");
        int a = extractIntParam(html, A_PATTERN, "app id (a)");
        // http flag: if true, RSA-encrypt the ivTemp for the UUID; if false (default), use raw bytes
        boolean httpFlag = html.contains(",http:true") || html.contains(",http:'true'")
                || html.contains("{http:true") || html.contains("{http:'true'");
        log.debug("Parsed session parameters: h={}, a={}, http={}", h, a, httpFlag);

        // ---- Step 3: Initialize session state -----------------------------
        PronoteSession session = new PronoteSession(baseUrl);
        session.setSessionHandle(h);
        session.setAppId(a);
        session.setCookies(lastFetchedCookies);
        log.debug("Captured {} cookies from login page", lastFetchedCookies.size());

        // ---- Step 4: Build UUID -------------------------------------------
        byte[] ivTemp = session.getAesIvTemp();
        byte[] uuidBytes = httpFlag ? CryptoHelper.rsaEncrypt(ivTemp) : ivTemp;
        String uuid = Base64.getEncoder().encodeToString(uuidBytes);

        // ---- Step 5: POST FonctionParametres (dataSec is plain JSON) ------
        ObjectNode fonctionData = jackson.createObjectNode()
                .put("Uuid", uuid)
                .put("identifiantNav", "");
        ObjectNode fonctionParams = jackson.createObjectNode();
        fonctionParams.set("data", fonctionData);

        JsonNode fonctionResponse = post(session, "FonctionParametres", fonctionParams);
        log.debug("FonctionParametres complete");

        // Parse General parameters from FonctionParametres:
        //   - PremierLundi → school-year first Monday (used for NumeroSemaine in timetable calls)
        //   - ListePeriodes → academic periods (used for DernieresNotes / DernieresEvaluations calls)
        try {
            JsonNode generalParams = navigateToData(fonctionResponse, "FonctionParametres");
            if (generalParams != null && generalParams.has("General")) {
                JsonNode general = generalParams.get("General");

                // PremierLundi
                JsonNode premierLundi = general.get("PremierLundi");
                if (premierLundi != null) {
                    String dateStr = premierLundi.has("V") ? premierLundi.get("V").asText("") : premierLundi.asText("");
                    if (!dateStr.isBlank()) {
                        LocalDate firstMonday = LocalDate.parse(
                                dateStr.trim().split(" ")[0],
                                DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                        session.setSchoolYearFirstMonday(firstMonday);
                        log.info("School-year first Monday: {}", firstMonday);
                    }
                }

                // ListePeriodes — needed for grades and competence evaluations
                JsonNode listePeriodes = general.get("ListePeriodes");
                if (listePeriodes != null) {
                    JsonNode periodesArray = listePeriodes.has("V") ? listePeriodes.get("V") : listePeriodes;
                    if (periodesArray != null && periodesArray.isArray()) {
                        List<PronoteSession.Period> periods = new ArrayList<>();
                        for (JsonNode p : periodesArray) {
                            String pId   = p.has("N") ? p.get("N").asText(null) : null;
                            String pName = p.has("L") ? p.get("L").asText("") : "";
                            int    pType = p.has("G") ? p.get("G").asInt(1)    : 1;
                            if (pId != null && !pId.isBlank()) {
                                PronoteSession.Period period = new PronoteSession.Period(pId, pName, pType);
                                // Extract period date range — needed for PagePresence (vie scolaire) calls
                                JsonNode dateDebut = p.get("dateDebut");
                                if (dateDebut != null) {
                                    String ds = dateDebut.has("V") ? dateDebut.get("V").asText("") : dateDebut.asText("");
                                    if (!ds.isBlank()) {
                                        try {
                                            period.setStartDate(LocalDate.parse(
                                                    ds.trim().split(" ")[0],
                                                    DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                                        } catch (DateTimeParseException e2) {
                                            log.debug("Could not parse period startDate '{}': {}", ds, e2.getMessage());
                                        }
                                    }
                                }
                                JsonNode dateFin = p.get("dateFin");
                                if (dateFin != null) {
                                    String ds = dateFin.has("V") ? dateFin.get("V").asText("") : dateFin.asText("");
                                    if (!ds.isBlank()) {
                                        try {
                                            period.setEndDate(LocalDate.parse(
                                                    ds.trim().split(" ")[0],
                                                    DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                                        } catch (DateTimeParseException e2) {
                                            log.debug("Could not parse period endDate '{}': {}", ds, e2.getMessage());
                                        }
                                    }
                                }
                                periods.add(period);
                            }
                        }
                        session.setPeriods(periods);
                        log.info("Parsed {} academic period(s): {}",
                                periods.size(),
                                periods.stream().map(PronoteSession.Period::getName)
                                       .reduce((x, y) -> x + ", " + y).orElse("(none)"));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not parse General params from FonctionParametres: {}", e.getMessage());
        }

        // After FonctionParametres: update IV to MD5(ivTemp)
        session.setAesIv(CryptoHelper.deriveSessionIv(ivTemp));

        // ---- Step 6: POST Identification ----------------------------------
        String username = config.getPronote().getUsername();
        String password = config.getPronote().getPassword();

        ObjectNode identData = jackson.createObjectNode()
                .put("genreConnexion", 0)
                .put("genreEspace", a)
                .put("identifiant", username)
                .put("pourENT", false)
                .put("enConnexionAuto", false)
                .put("demandeConnexionAuto", false)
                .put("demandeConnexionAppliMobile", false)
                .put("demandeConnexionAppliMobileJeton", false)
                .put("enConnexionAppliMobile", false)
                .put("uuidAppliMobile", "")
                .put("loginTokenSAV", "");
        ObjectNode identParams = jackson.createObjectNode();
        identParams.set("data", identData);

        JsonNode identResponse = post(session, "Identification", identParams);
        log.debug("Identification response received");

        JsonNode identDataNode = navigateToData(identResponse, "Identification");

        String challenge = getRequiredString(identDataNode, "challenge",
                "Identification response missing 'challenge'");
        String alea = identDataNode.has("alea") && !identDataNode.get("alea").isNull()
                ? identDataNode.get("alea").asText() : "";
        boolean modeCompLog = identDataNode.has("modeCompLog")
                && identDataNode.get("modeCompLog").asBoolean();
        boolean modeCompMdp = identDataNode.has("modeCompMdp")
                && identDataNode.get("modeCompMdp").asBoolean();

        if (modeCompLog) username = username.toLowerCase();
        if (modeCompMdp) password = password.toLowerCase();
        log.debug("Alea and challenge extracted; modeCompLog={}, modeCompMdp={}", modeCompLog, modeCompMdp);

        // ---- Step 7: Derive auth key -------------------------------------
        // motdepasse = SHA256(alea + password).hexdigest().upper()
        String sha256Hex = CryptoHelper.toHex(
                CryptoHelper.sha256((alea + password).getBytes(StandardCharsets.UTF_8))
        ).toUpperCase();
        byte[] authKey = CryptoHelper.md5((username + sha256Hex).getBytes(StandardCharsets.UTF_8));
        // NOTE: do NOT update session.aesKey here. The Authentification POST's 'no' field
        // must still be encrypted with the initial key (MD5("")), matching pronotepy's
        // _Communication.post which uses self.encryption (still initial key at this point).
        // authKey is applied only after_auth succeeds (for 'cle' decryption → final key).

        // ---- Step 8: Decrypt challenge, strip alea, re-encrypt -----------
        // pronotepy: aes_decrypt (with PKCS7 unpad) → dec.decode() → _enleverAlea (string chars) → encode() → aes_encrypt
        byte[] challengeBytes = CryptoHelper.fromHex(challenge);
        byte[] decryptedChallenge = CryptoHelper.aesDecrypt(challengeBytes, authKey, session.getAesIv());
        String decryptedStr = new String(decryptedChallenge, StandardCharsets.UTF_8);
        // _enleverAlea: keep only even-indexed characters (matches pronotepy's string-level operation)
        String stripped = enleverAlea(decryptedStr);
        byte[] strippedBytes = stripped.getBytes(StandardCharsets.UTF_8);
        byte[] reEncrypted = CryptoHelper.aesEncrypt(strippedBytes, authKey, session.getAesIv());
        String ch = CryptoHelper.toHex(reEncrypted);

        // ---- Step 9: POST Authentification --------------------------------
        ObjectNode authData = jackson.createObjectNode()
                .put("connexion", 0)
                .put("challenge", ch)
                .put("espace", a);
        ObjectNode authParams = jackson.createObjectNode();
        authParams.set("data", authData);

        JsonNode authResponse = post(session, "Authentification", authParams);
        log.debug("Authentification response received");

        JsonNode authDataNode = navigateToData(authResponse, "Authentification");

        // ---- Step 10: Re-derive session key from cle ---------------------
        if (authDataNode.has("cle") && !authDataNode.get("cle").isNull()) {
            String cleHex = authDataNode.get("cle").asText();
            byte[] decryptedCle = CryptoHelper.aesDecrypt(
                    CryptoHelper.fromHex(cleHex), authKey, session.getAesIv());
            String cleStr = new String(decryptedCle, StandardCharsets.UTF_8);
            byte[] cleBytes = parseCommaSeparatedBytes(cleStr);
            byte[] finalKey = CryptoHelper.md5(cleBytes);
            session.setAesKey(finalKey);
            log.debug("Session key re-derived from cle");
        } else {
            log.warn("No 'cle' in Authentification response; keeping auth key as session key");
        }

        // ---- Step 11: POST ParametresUtilisateur (required to init server-side page state) ----
        // pronotepy calls this immediately after after_auth; without it, subsequent data API calls
        // return G:1 "La page a expiré ! (1)"
        JsonNode puResponse = post(session, "ParametresUtilisateur", jackson.createObjectNode());
        log.debug("ParametresUtilisateur complete");

        // For parent accounts, extract the first child's ID (used as "membre" in Signature)
        // pronotepy: parametres_utilisateur["dataSec"]["data"]["ressource"]["listeRessources"][0]["N"]
        JsonNode puData = navigateToData(puResponse, "ParametresUtilisateur");
        if (puData != null && puData.has("ressource")) {
            JsonNode ressource = puData.get("ressource");
            JsonNode listeRessources = ressource.get("listeRessources");
            if (listeRessources == null && ressource.has("V")) {
                listeRessources = ressource.get("V").get("listeRessources");
            }
            if (listeRessources != null) {
                JsonNode items = listeRessources.has("V") ? listeRessources.get("V") : listeRessources;
                if (items != null && items.isArray() && items.size() > 0) {
                    JsonNode first = items.get(0);
                    JsonNode child = first.has("V") ? first.get("V") : first;
                    if (child.has("N")) {
                        session.setChildId(child.get("N").asText());
                        log.info("Child ID set for parent account: {}", session.getChildId());
                    }
                }
            }
        }
        if (session.getChildId() == null) {
            log.debug("No child ID found in ParametresUtilisateur (student account or unexpected structure)");
        }

        log.info("Authentication successful (session h={}, a={})", h, a);
        return session;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Sends a JSON-RPC POST to the Pronote appelfonction endpoint.
     * The {@code no} field is always AES-encrypted hex.
     * The {@code dataSec} field is plain JSON (assumes CrA=false).
     *
     * @return the {@code donneesSec} field from the response (plain or decrypted)
     */
    private JsonNode post(PronoteSession session, String functionName, ObjectNode params) {
        int order = session.nextOrder();
        String url = session.getBaseUrl() + "appelfonction/" + session.getAppId()
                + "/" + session.getSessionHandle() + "/" + order;

        String orderHex = CryptoHelper.toHex(
                CryptoHelper.aesEncrypt(
                        String.valueOf(order).getBytes(StandardCharsets.UTF_8),
                        session.getAesKey(), session.getAesIv()));

        // pronotepy always includes nom and numeroOrdre in dataSec (CrA=false mode)
        params.put("nom", functionName);
        params.put("numeroOrdre", orderHex);

        ObjectNode envelope = jackson.createObjectNode();
        envelope.put("session", session.getSessionHandle());
        envelope.put("no", orderHex);
        envelope.put("id", functionName);
        envelope.set("dataSec", params);  // plain JSON, not encrypted

        String envelopeJson;
        try {
            envelopeJson = jackson.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new AuthException("Failed to serialize request: " + e.getMessage(), e);
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(envelopeJson, MediaType.parse("application/json; charset=utf-8")));
        for (Map.Entry<String, String> cookie : session.getCookies().entrySet()) {
            reqBuilder.addHeader("Cookie", cookie.getKey() + "=" + cookie.getValue());
        }

        log.debug("POST {} ({}) body={}", functionName, url, envelopeJson);

        try (Response response = http.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new AuthException("HTTP " + response.code() + " on " + functionName + " call");
            }
            // Capture any cookies set by the server during the auth flow
            for (String header : response.headers("Set-Cookie")) {
                String[] parts = header.split(";")[0].split("=", 2);
                if (parts.length == 2) {
                    session.getCookies().put(parts[0].trim(), parts[1].trim());
                    log.debug("Cookie set by {}: {}={}", functionName, parts[0].trim(), parts[1].trim());
                }
            }
            ResponseBody body = response.body();
            if (body == null) throw new AuthException("Empty response from " + functionName);
            String responseJson = body.string();
            log.debug("Response from {}: {}", functionName, responseJson);

            JsonNode outer;
            try {
                outer = jackson.readTree(responseJson);
            } catch (Exception e) {
                throw new AuthException("Invalid JSON from " + functionName + ": " + e.getMessage(), e);
            }

            // Surface any top-level error
            if (outer.has("Erreur")) {
                JsonNode errNode = outer.get("Erreur");
                String msg = errNode.has("Titre") ? errNode.get("Titre").asText() : errNode.asText();
                throw new AuthException(functionName + " rejected by server: " + msg);
            }

            if (!outer.has("donneesSec")) {
                return outer;
            }

            JsonNode donneesSec = outer.get("donneesSec");
            if (donneesSec.isTextual()) {
                // CrA=true: donneesSec is an AES-encrypted hex string
                byte[] decrypted = CryptoHelper.aesDecrypt(
                        CryptoHelper.fromHex(donneesSec.asText()),
                        session.getAesKey(), session.getAesIv());
                try {
                    return jackson.readTree(new String(decrypted, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new AuthException("Failed to parse decrypted response from " + functionName, e);
                }
            } else {
                // CrA=false: donneesSec is a plain JSON object
                return donneesSec;
            }

        } catch (IOException e) {
            throw new AuthException("Network error during " + functionName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Navigates the donneesSec response to the inner data node.
     * Expected structure: {dataSec: {data: {...}}} or fallback to {donnees: {...}}.
     */
    private JsonNode navigateToData(JsonNode donneesSec, String functionName) {
        if (donneesSec == null) {
            throw new AuthException(functionName + " response donneesSec is null");
        }
        if (donneesSec.has("dataSec")) {
            JsonNode ds = donneesSec.get("dataSec");
            return ds.has("data") ? ds.get("data") : ds;
        }
        if (donneesSec.has("donnees")) {
            return donneesSec.get("donnees");
        }
        return donneesSec;
    }

    /** _enleverAlea: keep only even-indexed characters from the decrypted challenge (pronotepy string-level). */
    private static String enleverAlea(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i += 2) {
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    /** Parses a comma-separated decimal string like "1,2,3,..." into a byte array. */
    private static byte[] parseCommaSeparatedBytes(String s) {
        String[] parts = s.trim().split(",");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = (byte) Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private String fetchHtml(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 302 || response.code() == 301) {
                String location = response.header("Location", "");
                throw new AuthException("Pronote login page redirected to: " + location
                        + " — this instance may require ENT/SSO authentication.");
            }
            if (!response.isSuccessful()) {
                throw new AuthException("HTTP " + response.code() + " fetching login page: " + url);
            }
            lastFetchedCookies = new HashMap<>();
            for (String header : response.headers("Set-Cookie")) {
                String[] parts = header.split(";")[0].split("=", 2);
                if (parts.length == 2) {
                    lastFetchedCookies.put(parts[0].trim(), parts[1].trim());
                }
            }
            ResponseBody body = response.body();
            if (body == null) throw new AuthException("Empty response body from: " + url);
            return body.string();
        } catch (IOException e) {
            throw new AuthException("Network error fetching login page: " + e.getMessage(), e);
        }
    }

    private static int extractIntParam(String html, Pattern pattern, String name) {
        Matcher m = pattern.matcher(html);
        if (!m.find()) {
            throw new AuthException("Could not extract " + name + " from login page HTML.");
        }
        return Integer.parseInt(m.group(1));
    }

    private static String getRequiredString(JsonNode node, String field, String errorMsg) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw new AuthException(errorMsg);
        return value.asText();
    }

    private static String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    // -------------------------------------------------------------------------

    public static class AuthException extends RuntimeException {
        public AuthException(String message) { super(message); }
        public AuthException(String message, Throwable cause) { super(message, cause); }
    }
}
