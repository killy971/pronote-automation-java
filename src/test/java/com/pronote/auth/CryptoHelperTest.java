package com.pronote.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CryptoHelperTest {

    // -------------------------------------------------------------------------
    // AES round-trip
    // -------------------------------------------------------------------------

    @Test
    void aesEncryptDecryptRoundTrip() {
        byte[] key = CryptoHelper.md5(new byte[0]); // 16-byte MD5 of empty = initial key
        byte[] iv = new byte[16];                   // 16 zero bytes = initial IV
        byte[] plaintext = "Hello, Pronote!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = CryptoHelper.aesEncrypt(plaintext, key, iv);
        byte[] decrypted = CryptoHelper.aesDecrypt(ciphertext, key, iv);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void aesEncryptProducesDifferentBytes() {
        byte[] key = CryptoHelper.md5("somekey".getBytes(StandardCharsets.UTF_8));
        byte[] iv = CryptoHelper.md5("someiv".getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = "test data".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = CryptoHelper.aesEncrypt(plaintext, key, iv);
        assertFalse(Arrays.equals(plaintext, ciphertext));
    }

    @Test
    void aesEncryptLongData() {
        byte[] key = CryptoHelper.md5(new byte[0]);
        byte[] iv = new byte[16];
        byte[] plaintext = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = CryptoHelper.aesEncrypt(plaintext, key, iv);
        byte[] decrypted = CryptoHelper.aesDecrypt(ciphertext, key, iv);

        assertArrayEquals(plaintext, decrypted);
    }

    // -------------------------------------------------------------------------
    // Hex encoding
    // -------------------------------------------------------------------------

    @Test
    void hexRoundTrip() {
        byte[] original = {0x00, 0x0F, 0x10, (byte) 0xFF, 0x7A};
        String hex = CryptoHelper.toHex(original);
        assertEquals("000f10ff7a", hex);
        assertArrayEquals(original, CryptoHelper.fromHex(hex));
    }

    @Test
    void toHexEmptyArray() {
        assertEquals("", CryptoHelper.toHex(new byte[0]));
    }

    // -------------------------------------------------------------------------
    // MD5 / SHA256 known values
    // -------------------------------------------------------------------------

    @Test
    void md5EmptyInput() {
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        byte[] hash = CryptoHelper.md5(new byte[0]);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", CryptoHelper.toHex(hash));
    }

    @Test
    void sha256KnownValue() {
        // SHA256("abc") = ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469f...
        byte[] hash = CryptoHelper.sha256("abc".getBytes(StandardCharsets.UTF_8));
        assertTrue(CryptoHelper.toHex(hash).startsWith("ba7816bf"));
    }

    // -------------------------------------------------------------------------
    // IV / Key derivation
    // -------------------------------------------------------------------------

    @Test
    void deriveSessionIvIsMd5OfIvTemp() {
        byte[] ivTemp = CryptoHelper.randomBytes(16);
        byte[] expected = CryptoHelper.md5(ivTemp);
        byte[] actual = CryptoHelper.deriveSessionIv(ivTemp);
        assertArrayEquals(expected, actual);
    }

    @Test
    void initialAesKeyIsMd5OfEmpty() {
        byte[] expected = CryptoHelper.md5(new byte[0]);
        assertArrayEquals(expected, CryptoHelper.INITIAL_AES_KEY);
    }

    @Test
    void initialAesIvIsZeros() {
        byte[] iv = CryptoHelper.INITIAL_AES_IV;
        assertEquals(16, iv.length);
        for (byte b : iv) assertEquals(0, b);
    }

    @Test
    void derivePostLoginKeyIsDeterministic() {
        byte[] key1 = CryptoHelper.derivePostLoginKey("user", "alea123", "pass");
        byte[] key2 = CryptoHelper.derivePostLoginKey("user", "alea123", "pass");
        assertArrayEquals(key1, key2);
    }

    @Test
    void derivePostLoginKeyVariesWithPassword() {
        byte[] key1 = CryptoHelper.derivePostLoginKey("user", "alea", "pass1");
        byte[] key2 = CryptoHelper.derivePostLoginKey("user", "alea", "pass2");
        assertFalse(Arrays.equals(key1, key2));
    }

    // -------------------------------------------------------------------------
    // RSA smoke test (just verify no exception and correct output length)
    // -------------------------------------------------------------------------

    @Test
    void rsaEncryptProducesCorrectOutputLength() {
        // RSA-1024 always produces 128-byte output
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = CryptoHelper.rsaEncrypt(data);
        assertEquals(128, encrypted.length);
    }
}
