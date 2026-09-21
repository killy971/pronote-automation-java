package com.pronote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins step 10 of the login flow: the session key is re-derived from {@code cle}, and a response
 * without one is a rejected login rather than a session to carry on with.
 *
 * <p>Synthetic keys only, no network. See {@link PronoteAuthenticator#sessionKeyFromCle}.
 */
class PronoteAuthenticatorSessionKeyTest {

    private static final byte[] AUTH_KEY = CryptoHelper.md5("SYN_AUTH_KEY".getBytes(StandardCharsets.UTF_8));
    private static final byte[] IV = CryptoHelper.md5("SYN_IV".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper jackson = new ObjectMapper();

    @Test
    void sessionKeyFromCle_derivesMd5OfTheDecryptedByteList() {
        byte[] keyMaterial = {1, 2, 3, 4, 5};
        String asDecimalList = "1,2,3,4,5";
        String cleHex = CryptoHelper.toHex(CryptoHelper.aesEncrypt(
                asDecimalList.getBytes(StandardCharsets.UTF_8), AUTH_KEY, IV));

        ObjectNode data = jackson.createObjectNode().put("cle", cleHex);

        assertArrayEquals(CryptoHelper.md5(keyMaterial),
                PronoteAuthenticator.sessionKeyFromCle(data, AUTH_KEY, IV));
    }

    @Test
    void sessionKeyFromCle_missingCle_failsTheLogin() {
        // The server's way of saying "rejected" — it still answers 200. Carrying on with the auth
        // key produced a session that decrypted every later response to {}, which the pipeline
        // then read as the school having deleted everything.
        ObjectNode data = jackson.createObjectNode().put("derniereConnexion", "2030-01-01");

        PronoteAuthenticator.AuthException e = assertThrows(PronoteAuthenticator.AuthException.class,
                () -> PronoteAuthenticator.sessionKeyFromCle(data, AUTH_KEY, IV));
        assertTrue(e.getMessage().contains("cle"), e.getMessage());
    }

    @Test
    void sessionKeyFromCle_nullCle_failsTheLogin() {
        ObjectNode data = jackson.createObjectNode();
        data.putNull("cle");

        assertThrows(PronoteAuthenticator.AuthException.class,
                () -> PronoteAuthenticator.sessionKeyFromCle(data, AUTH_KEY, IV));
    }

    @Test
    void sessionKeyFromCle_nullResponse_failsTheLogin() {
        assertThrows(PronoteAuthenticator.AuthException.class,
                () -> PronoteAuthenticator.sessionKeyFromCle(null, AUTH_KEY, IV));
    }
}
