package com.pronote.auth;

import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.encodings.PKCS1Encoding;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.params.RSAKeyParameters;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Low-level cryptographic operations for the Pronote protocol.
 *
 * <p>All algorithms follow the pronotepy reference implementation:
 * <ul>
 *   <li>AES-CBC 128-bit with PKCS7 padding</li>
 *   <li>RSA-1024 PKCS1v1.5 (encrypt-only, for IV exchange)</li>
 *   <li>MD5 and SHA-256 for key/IV derivation</li>
 * </ul>
 *
 * <p>Wire encoding: encrypted byte arrays are represented as lowercase hex strings.
 */
public class CryptoHelper {

    /**
     * The well-known RSA-1024 public key used by Pronote for the initial IV exchange.
     * This modulus is hardcoded in pronotepy's _Encryption class.
     */
    static final BigInteger RSA_MODULUS = new BigInteger(
            "130337874517286041778445012253514395801341480334668979416920989365464528904618150245388048105865059387076357492684573172203245221386376405947824377827224846860699130638566643129067735803555082190977267155957271492183684665050351182476506458843580431717209261903043895605014125081521285387341454154194253026277",
            10);
    static final BigInteger RSA_EXPONENT = BigInteger.valueOf(65537L);

    /** Null/empty MD5 digest — the initial AES key before the session is established. */
    public static final byte[] INITIAL_AES_KEY = md5(new byte[0]);

    /** 16 zero bytes — the initial AES IV before the server responds. */
    public static final byte[] INITIAL_AES_IV = new byte[16];

    private CryptoHelper() {}

    // -------------------------------------------------------------------------
    // AES
    // -------------------------------------------------------------------------

    /**
     * Encrypts {@code data} with AES-CBC/PKCS7.
     *
     * @param data plaintext bytes
     * @param key  16-byte AES key
     * @param iv   16-byte IV
     * @return ciphertext bytes
     */
    public static byte[] aesEncrypt(byte[] data, byte[] key, byte[] iv) {
        return aesCbc(data, key, iv, true);
    }

    /**
     * Decrypts {@code data} with AES-CBC/PKCS7.
     *
     * @param data ciphertext bytes
     * @param key  16-byte AES key
     * @param iv   16-byte IV
     * @return plaintext bytes
     * @throws CryptoException if padding is incorrect (indicates wrong key/IV or tampered data)
     */
    public static byte[] aesDecrypt(byte[] data, byte[] key, byte[] iv) {
        return aesCbc(data, key, iv, false);
    }

    /**
     * Decrypts {@code data} with AES-CBC, returning raw bytes WITHOUT stripping PKCS7 padding.
     *
     * <p>Used for the Pronote challenge, which must be processed at the raw-block level to match
     * pronotepy's behaviour (Python's {@code AES.decrypt} does not unpad the result).
     */
    public static byte[] aesDecryptRaw(byte[] data, byte[] key, byte[] iv) {
        BlockCipher engine = AESEngine.newInstance();
        BufferedBlockCipher cipher = new BufferedBlockCipher(CBCBlockCipher.newInstance(engine));
        cipher.init(false, new ParametersWithIV(new KeyParameter(key), iv));
        byte[] output = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, output, 0);
        try {
            len += cipher.doFinal(output, len);
        } catch (CryptoException e) {
            throw new PronoteAuthException("AES raw decryption failed: " + e.getMessage(), e);
        }
        if (len == output.length) return output;
        byte[] trimmed = new byte[len];
        System.arraycopy(output, 0, trimmed, 0, len);
        return trimmed;
    }

    private static byte[] aesCbc(byte[] data, byte[] key, byte[] iv, boolean encrypt) {
        BlockCipher engine = AESEngine.newInstance();
        BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
                CBCBlockCipher.newInstance(engine), new PKCS7Padding());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));

        byte[] output = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, output, 0);
        try {
            len += cipher.doFinal(output, len);
        } catch (CryptoException e) {
            throw new PronoteAuthException("AES " + (encrypt ? "encryption" : "decryption") + " failed: " + e.getMessage(), e);
        }

        if (len == output.length) return output;
        byte[] trimmed = new byte[len];
        System.arraycopy(output, 0, trimmed, 0, len);
        return trimmed;
    }

    // -------------------------------------------------------------------------
    // RSA
    // -------------------------------------------------------------------------

    /**
     * Encrypts {@code data} with RSA-1024/PKCS1v1.5 using the Pronote public key.
     */
    public static byte[] rsaEncrypt(byte[] data) {
        RSAKeyParameters pubKey = new RSAKeyParameters(false, RSA_MODULUS, RSA_EXPONENT);
        AsymmetricBlockCipher cipher = new PKCS1Encoding(new RSAEngine());
        cipher.init(true, pubKey);
        try {
            return cipher.processBlock(data, 0, data.length);
        } catch (CryptoException e) {
            throw new PronoteAuthException("RSA encryption failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Digests
    // -------------------------------------------------------------------------

    /** Returns the MD5 digest of {@code input}. */
    public static byte[] md5(byte[] input) {
        MD5Digest digest = new MD5Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    /** Returns the SHA-256 digest of {@code input}. */
    public static byte[] sha256(byte[] input) {
        SHA256Digest digest = new SHA256Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    // -------------------------------------------------------------------------
    // Key / IV derivation (mirrors pronotepy's _Encryption class)
    // -------------------------------------------------------------------------

    /**
     * Derives the session IV from the client's temporary random IV.
     * Formula: {@code MD5(ivTemp)}
     */
    public static byte[] deriveSessionIv(byte[] ivTemp) {
        return md5(ivTemp);
    }

    /**
     * Derives the post-login AES key from credentials and the server's alea.
     *
     * <p>Formula (from pronotepy):
     * {@code MD5( username_bytes + SHA256( (alea + password).getBytes(UTF-8) ) )}
     *
     * @param username Pronote username
     * @param alea     random string sent by the server in the challenge response
     * @param password Pronote password
     * @return 16-byte AES key
     */
    public static byte[] derivePostLoginKey(String username, String alea, String password) {
        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] aleaPasswordHash = sha256((alea + password).getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[usernameBytes.length + aleaPasswordHash.length];
        System.arraycopy(usernameBytes, 0, combined, 0, usernameBytes.length);
        System.arraycopy(aleaPasswordHash, 0, combined, usernameBytes.length, aleaPasswordHash.length);

        return md5(combined);
    }

    // -------------------------------------------------------------------------
    // Encoding helpers
    // -------------------------------------------------------------------------

    /** Converts a byte array to a lowercase hex string. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Parses a hex string into a byte array. */
    public static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /** Generates {@code n} cryptographically random bytes. */
    public static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    // -------------------------------------------------------------------------

    public static class PronoteAuthException extends RuntimeException {
        public PronoteAuthException(String message) { super(message); }
        public PronoteAuthException(String message, Throwable cause) { super(message, cause); }
    }
}
