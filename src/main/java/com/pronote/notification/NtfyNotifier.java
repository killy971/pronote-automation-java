package com.pronote.notification;

import com.pronote.config.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Sends push notifications via ntfy (https://ntfy.sh).
 *
 * <p>Uses a plain HTTP POST to {@code {serverUrl}/{topic}} with the message body
 * as plain text and metadata in standard ntfy headers.
 *
 * <p>See: https://docs.ntfy.sh/publish/
 */
public class NtfyNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NtfyNotifier.class);
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");

    private final AppConfig.NtfyConfig config;
    private final OkHttpClient http;

    public NtfyNotifier(AppConfig.NtfyConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void send(NotificationPayload payload) throws NotificationException {
        String url = normalizeUrl(config.getServerUrl()) + config.getTopic();

        Request.Builder req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(payload.body(), TEXT))
                .addHeader("Title", encodeHeaderValue(payload.title()))
                .addHeader("Priority", ntfyPriority(payload.priority()));

        if (!payload.tags().isEmpty()) {
            req.addHeader("Tags", encodeHeaderValue(String.join(",", payload.tags())));
        }

        if (config.getToken() != null && !config.getToken().isBlank()) {
            req.addHeader("Authorization", "Bearer " + config.getToken());
        }

        log.debug("Sending ntfy notification to topic '{}'", config.getTopic());

        try (Response response = http.newCall(req.build()).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "(empty)";
                throw new NotificationException("ntfy returned HTTP " + response.code() + ": " + responseBody);
            }
            log.info("ntfy notification sent (title: '{}')", payload.title());
        } catch (NotificationException e) {
            throw e;
        } catch (IOException e) {
            throw new NotificationException("Network error sending ntfy notification: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new NotificationException("Unexpected error sending ntfy notification: " + e.getMessage(), e);
        }
    }

    private static String ntfyPriority(NotificationPayload.Priority p) {
        return switch (p) {
            case LOW    -> "low";
            case NORMAL -> "default";
            case HIGH   -> "high";
        };
    }

    private static String normalizeUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * RFC 2047-encodes the value if it contains non-ASCII characters.
     * OkHttp rejects non-ASCII bytes in header values; ntfy accepts RFC 2047-encoded headers
     * and decodes them correctly on the client side.
     */
    private static String encodeHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                String b64 = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
                return "=?UTF-8?B?" + b64 + "?=";
            }
        }
        return value;
    }
}
