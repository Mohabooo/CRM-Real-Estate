package com.rescrm.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Password hashing")
class Pbkdf2PasswordHasherTest {

    private final PasswordHasher hasher = new Pbkdf2PasswordHasher();

    @Test
    @DisplayName("verifies the correct password and rejects a wrong one")
    void round_trips() {
        String stored = hasher.hash("correct horse battery staple");
        assertThat(hasher.matches("correct horse battery staple", stored)).isTrue();
        assertThat(hasher.matches("Correct horse battery staple", stored)).isFalse();
        assertThat(hasher.matches("", stored)).isFalse();
    }

    @Test
    @DisplayName("never stores the password itself")
    void does_not_store_the_password() {
        String password = "a-very-memorable-passphrase";
        assertThat(hasher.hash(password)).doesNotContain(password);
    }

    @Test
    @DisplayName("salts, so the same password hashes differently every time")
    void salted() {
        assertThat(hasher.hash("same")).isNotEqualTo(hasher.hash("same"));
    }

    @Test
    @DisplayName("records its algorithm so a future encoder can migrate it")
    void self_describing_format() {
        assertThat(hasher.hash("x")).startsWith("pbkdf2-sha256$");
        assertThat(hasher.hash("x").split("\\$")).hasSize(4);
    }

    @Test
    @DisplayName("treats an unrecognised stored value as no match rather than crashing")
    void unknown_format_does_not_match() {
        assertThat(hasher.matches("x", "bcrypt$something")).isFalse();
        assertThat(hasher.matches("x", "nonsense")).isFalse();
        assertThat(hasher.matches("x", null)).isFalse();
    }

    @Test
    @DisplayName("refuses to hash an empty password")
    void refuses_empty() {
        assertThatThrownBy(() -> hasher.hash("")).isInstanceOf(IllegalArgumentException.class);
    }
}
