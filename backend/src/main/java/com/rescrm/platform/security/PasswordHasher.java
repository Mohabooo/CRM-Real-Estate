package com.rescrm.platform.security;

/**
 * Hashes and verifies passwords.
 *
 * <p>An interface rather than a concrete call so the algorithm is one class, swappable without
 * touching any caller. Doc 28 section 6 names BCrypt or Argon2; neither is available without
 * adding a dependency, and authentication itself is not part of this increment, so
 * {@link Pbkdf2PasswordHasher} ships as a standards-based stand-in whose stored format
 * identifies itself for migration. This is recorded as a deviation in the Epic 1 report.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);
}
