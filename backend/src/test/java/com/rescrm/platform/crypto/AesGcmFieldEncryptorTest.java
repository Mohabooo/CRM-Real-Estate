package com.rescrm.platform.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Field encryption for the national identifier (doc 19 F3, doc 28 section 13).
 */
@DisplayName("AES-GCM field encryption")
class AesGcmFieldEncryptorTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("unit-test-key-32-bytes-exactly!!".getBytes());

    private final AesGcmFieldEncryptor encryptor = new AesGcmFieldEncryptor(KEY);

    @Test
    @DisplayName("a value survives a round trip")
    void round_trip() {
        String ciphertext = encryptor.encrypt("29801011234567");

        assertThat(ciphertext).isNotEqualTo("29801011234567");
        assertThat(encryptor.decrypt(ciphertext)).isEqualTo("29801011234567");
    }

    @Test
    @DisplayName("the same value encrypts differently every time")
    void nonce_makes_ciphertexts_unique() {
        String first = encryptor.encrypt("29801011234567");
        String second = encryptor.encrypt("29801011234567");

        // Without this, anyone with read access to the table could tell which customers share
        // an identifier, which is most of what the encryption was meant to prevent.
        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(first)).isEqualTo(encryptor.decrypt(second));
    }

    @Test
    @DisplayName("an altered ciphertext fails to decrypt rather than yielding a different number")
    void tampering_is_detected() {
        String ciphertext = encryptor.encrypt("29801011234567");
        byte[] raw = Base64.getDecoder().decode(ciphertext);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("altered");
    }

    @Test
    @DisplayName("an absent value stays absent in both directions")
    void null_and_blank_pass_through() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.encrypt("  ")).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("with no key configured, encrypting fails loudly instead of storing plaintext")
    void unconfigured_key_fails_loudly() {
        AesGcmFieldEncryptor unconfigured = new AesGcmFieldEncryptor("");

        assertThatThrownBy(() -> unconfigured.encrypt("29801011234567"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field-encryption-key");
    }

    @Test
    @DisplayName("a key of the wrong length is rejected at construction")
    void wrong_length_key_is_rejected() {
        String tooShort = Base64.getEncoder().encodeToString("sixteen-bytes!!!".getBytes());

        assertThatThrownBy(() -> new AesGcmFieldEncryptor(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256");
    }

    @Test
    @DisplayName("a key that is not base64 is rejected at construction")
    void non_base64_key_is_rejected() {
        assertThatThrownBy(() -> new AesGcmFieldEncryptor("not base64 at all !!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }
}
