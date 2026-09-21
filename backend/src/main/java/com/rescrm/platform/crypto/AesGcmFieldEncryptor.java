package com.rescrm.platform.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM, with the key supplied by configuration.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: a ciphertext altered in
 * the database fails to decrypt instead of yielding a different national identifier. The
 * random 12-byte nonce is stored in front of the ciphertext, so the same number encrypted
 * twice produces different stored values and an attacker with read access cannot tell which
 * customers share an identifier.
 *
 * <p>There is deliberately no default key. A hard-coded fallback is the kind of thing that
 * survives into production and turns encryption at rest into an elaborate way of storing
 * plaintext, so an unconfigured key fails loudly on first use instead.
 */
@Component
public class AesGcmFieldEncryptor implements FieldEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    public AesGcmFieldEncryptor(
            @Value("${crm.security.field-encryption-key:}") String configuredKey) {
        this.key = configuredKey == null || configuredKey.isBlank() ? null : toKey(configuredKey);
    }

    private static SecretKey toKey(String configuredKey) {
        byte[] material;
        try {
            material = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "crm.security.field-encryption-key must be base64", e);
        }
        if (material.length != KEY_BYTES) {
            throw new IllegalStateException("crm.security.field-encryption-key must decode to "
                    + KEY_BYTES + " bytes (AES-256), got " + material.length);
        }
        return new SecretKeySpec(material, "AES");
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "No field encryption key is configured. Set crm.security.field-encryption-key "
                            + "to a base64 32-byte value; sensitive fields are not written or read "
                            + "without it.");
        }
        return key;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt field", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(ciphertext);
            if (envelope.length <= NONCE_BYTES) {
                throw new IllegalStateException("Stored value is too short to be valid ciphertext");
            }
            byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Includes the authentication-tag failure, which means the stored value was
            // altered. Surfacing that as a failure rather than a garbled string is the point
            // of using an authenticated mode.
            throw new IllegalStateException("Could not decrypt field; it may have been altered", e);
        }
    }
}
