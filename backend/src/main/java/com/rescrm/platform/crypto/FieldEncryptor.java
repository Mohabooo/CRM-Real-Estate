package com.rescrm.platform.crypto;

/**
 * Encrypts a single sensitive field at rest.
 *
 * <p>Introduced for the customer national identifier, which doc 22 stores as
 * {@code national_id_enc} and doc 28 section 6 restricts beyond record access. An interface
 * so the algorithm and the key source can change without touching a caller.
 */
public interface FieldEncryptor {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
