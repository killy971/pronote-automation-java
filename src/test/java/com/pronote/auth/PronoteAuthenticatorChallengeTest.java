package com.pronote.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the two challenge-solving variants of the Pronote login flow.
 *
 * <p>See {@link PronoteAuthenticator#solveChallenge} — synthetic keys only, no network.
 */
class PronoteAuthenticatorChallengeTest {

    private static final byte[] KEY = CryptoHelper.md5("SYN_AUTH_KEY".getBytes(StandardCharsets.UTF_8));
    private static final byte[] IV = CryptoHelper.md5("SYN_IV".getBytes(StandardCharsets.UTF_8));

    @Test
    void solveChallenge_legacyVariant_decryptsStripsAndReEncrypts() {
        // The legacy server sends AES(authKey) of a token whose odd-indexed chars are alea padding.
        String token = "ABCDEFGH";
        StringBuilder interleaved = new StringBuilder();
        for (char c : token.toCharArray()) {
            interleaved.append(c).append('x');
        }
        String challenge = CryptoHelper.toHex(CryptoHelper.aesEncrypt(
                interleaved.toString().getBytes(StandardCharsets.UTF_8), KEY, IV));

        String expected = CryptoHelper.toHex(CryptoHelper.aesEncrypt(
                token.getBytes(StandardCharsets.UTF_8), KEY, IV));

        assertEquals(expected, PronoteAuthenticator.solveChallenge(challenge, KEY, IV));
    }

    @Test
    void solveChallenge_opaqueVariant_reEncryptsRawChallengeString() {
        // PRONOTE 2026.2.5+ sends an opaque nonce that is not an authKey ciphertext, so the
        // legacy path fails to unpad and the raw challenge string is re-encrypted as-is.
        String opaque = firstNonDecryptableChallenge();

        String expected = CryptoHelper.toHex(CryptoHelper.aesEncrypt(
                opaque.getBytes(StandardCharsets.UTF_8), KEY, IV));

        assertEquals(expected, PronoteAuthenticator.solveChallenge(opaque, KEY, IV));
    }

    @Test
    void solveChallenge_variantsProduceDifferentAnswers() {
        String opaque = firstNonDecryptableChallenge();
        String direct = CryptoHelper.toHex(CryptoHelper.aesEncrypt(
                opaque.getBytes(StandardCharsets.UTF_8), KEY, IV));
        // Sanity: the fallback is not accidentally equal to encrypting the decoded bytes.
        assertNotEquals(direct, CryptoHelper.toHex(CryptoHelper.aesEncrypt(
                CryptoHelper.fromHex(opaque), KEY, IV)));
    }

    /** A 16-byte hex challenge that is not valid ciphertext under {@link #KEY} — deterministic. */
    private static String firstNonDecryptableChallenge() {
        for (int i = 0; i < 512; i++) {
            String candidate = String.format("%032X", i);
            try {
                CryptoHelper.aesDecrypt(CryptoHelper.fromHex(candidate), KEY, IV);
            } catch (RuntimeException e) {
                return candidate;
            }
        }
        return fail("no non-decryptable candidate found");
    }
}
