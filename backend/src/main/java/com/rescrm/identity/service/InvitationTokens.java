package com.rescrm.identity.service;

import com.rescrm.platform.security.RandomTokens;

/**
 * Single-use invitation tokens.
 *
 * <p>A thin naming layer over {@link RandomTokens}, which sessions use as well. The
 * generation and hashing are identical and deliberately live in one place: two copies of
 * security-critical code is one copy too many, and the one that gets improved is never the
 * one being read.
 */
public final class InvitationTokens {

    private InvitationTokens() {
    }

    /** A fresh token and its hash. */
    public record IssuedToken(String rawToken, String tokenHash) {
    }

    public static IssuedToken issue() {
        RandomTokens.IssuedToken issued = RandomTokens.issue();
        return new IssuedToken(issued.rawToken(), issued.tokenHash());
    }

    public static String hash(String rawToken) {
        return RandomTokens.hash(rawToken);
    }
}
