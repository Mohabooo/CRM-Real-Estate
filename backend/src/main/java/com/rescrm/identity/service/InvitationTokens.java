package com.rescrm.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates single-use invitation tokens and the hashes that are stored in their place.
 *
 * <p>256 bits from {@link SecureRandom}, so guessing one is not a realistic attack, and only
 * the SHA-256 of it is persisted. Unlike a password this needs no slow KDF: the token is
 * already high-entropy random, so there is nothing for an offline attacker to grind.
 *
 * <p>The raw token exists exactly once, in the return value of {@link #issue()}. It is handed
 * to the caller to deliver and is never written to the database or to a log.
 */
public final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private InvitationTokens() {
    }

    /** A fresh token and its hash. */
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
