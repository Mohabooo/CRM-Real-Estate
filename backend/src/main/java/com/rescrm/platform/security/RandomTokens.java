package com.rescrm.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * High-entropy opaque tokens and the hashes stored in their place.
 *
 * <p>Used for invitation tokens and session cookies. Both are bearer credentials with the
 * same two properties: the value is generated rather than chosen, and only its digest is
 * persisted, so a disclosure of the table yields nothing anyone can present.
 *
 * <p>256 bits from {@link SecureRandom}. Unlike a password this needs no slow key-derivation
 * function — the value is already uniformly random, so there is nothing for an offline
 * attacker to grind. Using one here would cost latency on every request without buying
 * anything.
 *
 * <p>Comparison of the stored hash happens in the database, by an equality predicate on an
 * indexed column. That is not constant-time; it does not need to be, because what is being
 * compared is a digest of a value the attacker would have to guess in full to influence.
 */
public final class RandomTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private RandomTokens() {
    }

    /** A fresh token and its hash. The raw value exists only in this return value. */
    public record IssuedToken(String rawToken, String tokenHash) {
    }

    public static IssuedToken issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(rawToken, hash(rawToken));
    }

    public static String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }
}
