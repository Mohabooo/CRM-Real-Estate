package com.rescrm.identity.service;

import com.rescrm.identity.domain.Session;
import com.rescrm.identity.domain.User;
import com.rescrm.identity.repository.SessionRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.PasswordHasher;
import com.rescrm.platform.security.RandomTokens;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.PlatformAuthenticationLookup;
import com.rescrm.platform.tenancy.PlatformAuthenticationLookup.SessionIdentity;
import com.rescrm.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing in, signing out, and turning a cookie back into a caller.
 *
 * <p>Doc 28 section 6 allows either server-side sessions or a short-lived JWT. This is the
 * session half, chosen because E1-S3 requires that deactivating a user takes effect: a
 * self-contained token keeps working until it expires, and the usual remedy — a revocation
 * list — reintroduces exactly the server state the token was meant to avoid. Revoking a row
 * is immediate and needs no second mechanism.
 *
 * <p><b>Why the work happens in a nested bean.</b> The tenant is not known when a request
 * arrives; finding it is the first thing authentication does. But
 * {@code TenantAwareDataSource} stamps a connection with whatever {@code TenantContext} holds
 * at the moment the connection is taken, so a transaction opened before the tenant is
 * resolved would run under the empty binding, which every policy treats as matching nothing.
 * So the sequence is: resolve the tenant with no transaction open, establish it, and only
 * then cross a bean boundary into a transactional method. A {@code @Transactional} method
 * called on {@code this} would not be proxied at all, which is the same lesson the CSV import
 * and the expiry sweep each learned once.
 *
 * <p><b>Why every failure looks the same.</b> An unknown company, an unknown address, a wrong
 * password and a deactivated account all produce one 401 with one message. Distinguishing
 * them would turn the login form into an oracle for which companies exist and who works
 * there, which is a disclosure in a system whose whole premise is that tenants cannot see
 * each other.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * One message for every way signing in can fail. See the class comment: the alternative
     * is a user-enumeration oracle wearing a helpful label.
     */
    private static final String REFUSED = "Those sign-in details are not correct";

    private final PlatformAuthenticationLookup lookup;
    private final TenantScopedAuthentication scoped;

    public AuthenticationService(PlatformAuthenticationLookup lookup,
                                 TenantScopedAuthentication scoped) {
        this.lookup = lookup;
        this.scoped = scoped;
    }

    /** A completed sign-in: the cookie value to set, and who the caller now is. */
    public record SignedIn(String rawToken, AuthenticatedPrincipal principal, User user,
                           OffsetDateTime expiresAt) {
    }

    public SignedIn signIn(String tenantSlug, String email, String rawPassword) {
        String slug = tenantSlug == null ? "" : tenantSlug.trim().toLowerCase(Locale.ROOT);
        UUID tenantId = lookup.activeTenantBySlug(slug).orElseThrow(() -> {
            // No tenant, so nowhere to write an audit row. The log line is the record.
            log.info("Sign-in refused: no active tenant for company key");
            return refused();
        });
        return TenantContext.callAs(tenantId,
                () -> scoped.signIn(tenantId, email, rawPassword));
    }

    /**
     * Turns a cookie into a caller, or into nothing.
     *
     * <p>Returns empty rather than throwing for every rejection: this runs inside the
     * authentication filter, whose job is to answer 401 uniformly, and a thrown exception
     * there would escape the error-handling the rest of the application uses.
     */
    public Optional<AuthenticatedPrincipal> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<SessionIdentity> identity =
                lookup.liveSessionByTokenHash(RandomTokens.hash(rawToken));
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        UUID tenantId = identity.get().tenantId();
        return TenantContext.callAs(tenantId, () -> scoped.activate(identity.get()));
    }

    /** Ends the session behind a cookie. Unknown or already-ended cookies are not an error. */
    public void signOut(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        lookup.liveSessionByTokenHash(RandomTokens.hash(rawToken)).ifPresent(identity ->
                TenantContext.callAs(identity.tenantId(), () -> {
                    scoped.signOut(identity);
                    return null;
                }));
    }

    private static ApiException refused() {
        return new ApiException(ErrorCode.UNAUTHORIZED, REFUSED);
    }

    /**
     * Everything that happens once the tenant is established.
     *
     * <p>A separate bean so Spring's proxy applies — see the outer class comment.
     */
    @Service
    public static class TenantScopedAuthentication {

        private final UserRepository users;
        private final SessionRepository sessions;
        private final PasswordHasher passwordHasher;
        private final AuditWriter audit;
        private final Clock clock;
        private final Duration idleTimeout;
        private final Duration absoluteLifetime;

        public TenantScopedAuthentication(
                UserRepository users, SessionRepository sessions,
                PasswordHasher passwordHasher, AuditWriter audit, Clock clock,
                @Value("${crm.security.session.idle-timeout:PT8H}") Duration idleTimeout,
                @Value("${crm.security.session.absolute-lifetime:P7D}") Duration absoluteLifetime) {
            this.users = users;
            this.sessions = sessions;
            this.passwordHasher = passwordHasher;
            this.audit = audit;
            this.clock = clock;
            this.idleTimeout = idleTimeout;
            this.absoluteLifetime = absoluteLifetime;
        }

        @Transactional
        public SignedIn signIn(UUID tenantId, String email, String rawPassword) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            User user = users.findByTenantIdAndEmail(tenantId, User.normalizeEmail(email))
                    .orElse(null);

            // The password is verified even when no user was found, against a hash that
            // cannot match. Skipping it would make a missing address measurably faster to
            // reject than a wrong password, which is the same disclosure the shared message
            // above exists to prevent, delivered by a stopwatch instead.
            boolean passwordMatches = passwordHasher.matches(
                    rawPassword == null ? "" : rawPassword,
                    user == null ? unmatchableHash() : user.passwordHash());

            if (user == null || !passwordMatches || !user.isActive()) {
                recordRefusal(tenantId, email, user);
                throw refused();
            }

            RandomTokens.IssuedToken token = RandomTokens.issue();
            Session session = Session.issue(tenantId, user.id(), token.tokenHash(), now,
                    idleTimeout, absoluteLifetime);
            session.recordActor(user.id(), true);
            Session saved = sessions.saveAndFlush(session);

            AuthenticatedPrincipal principal = principalOf(user);

            // Set so the audit row names the user who just signed in rather than recording a
            // null actor, which V2 reserves for SYS transitions. Cleared again immediately:
            // this is a public path, so the authentication filter did not run and will not
            // clear it on the way out.
            SecurityContext.set(principal);
            try {
                audit.recordCreation(AuditAction.SESSION_STARTED, "Session", saved.id(),
                        Map.of("userId", user.id().toString(),
                                "expiresAt", saved.expiresAt().toString(),
                                "absoluteExpiresAt", saved.absoluteExpiresAt().toString()));
            } finally {
                SecurityContext.clear();
            }

            return new SignedIn(token.rawToken(), principal, user, saved.expiresAt());
        }

        /**
         * Loads the session and its user, checks both are still good, and slides the expiry.
         *
         * <p>The user is re-read on every request rather than cached in the session row. A
         * role change, a branch move or a deactivation has to take effect on the next
         * request, not whenever the session happens to end — which is the whole reason this
         * is a session and not a token.
         */
        @Transactional
        public Optional<AuthenticatedPrincipal> activate(SessionIdentity identity) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            Session session = sessions
                    .findByTenantIdAndId(identity.tenantId(), identity.sessionId())
                    .orElse(null);
            if (session == null || !session.isLiveAt(now)) {
                return Optional.empty();
            }

            User user = users.findByTenantIdAndId(identity.tenantId(), identity.userId())
                    .orElse(null);
            if (user == null || !user.isActive()) {
                // Belt and braces: deactivation revokes sessions, so this should already be
                // unreachable. It stays because "should already" is not a guarantee, and the
                // cost of being wrong here is a deactivated person still holding a session.
                session.revoke(now, "User is no longer active");
                sessions.save(session);
                return Optional.empty();
            }

            if (session.touch(now, idleTimeout)) {
                sessions.save(session);
            }
            return Optional.of(principalOf(user));
        }

        @Transactional
        public void signOut(SessionIdentity identity) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            sessions.findByTenantIdAndId(identity.tenantId(), identity.sessionId())
                    .ifPresent(session -> {
                        if (session.isRevoked()) {
                            return;
                        }
                        session.revoke(now, "Signed out");
                        Session saved = sessions.save(session);
                        audit.record(AuditAction.SESSION_ENDED, "Session", saved.id(),
                                Map.of("status", "live"),
                                Map.of("status", "revoked", "reason", "Signed out"), null);
                    });
        }

        /**
         * Ends every session a user holds.
         *
         * <p>Joins the caller's transaction rather than opening its own, so that revoking
         * sessions and the change that caused it — a deactivation, a password change —
         * either both happen or neither does. A deactivation that committed while its
         * session revocation rolled back would leave someone signed in who is not supposed
         * to be, which is the one outcome worth engineering against.
         */
        @Transactional(propagation = Propagation.MANDATORY)
        public int revokeAllForUser(UUID tenantId, UUID userId, String reason) {
            return sessions.revokeAllForUser(tenantId, userId,
                    OffsetDateTime.now(clock), reason);
        }

        private void recordRefusal(UUID tenantId, String email, User user) {
            try {
                audit.recordCreation(AuditAction.SESSION_REFUSED, "User",
                        user == null ? tenantId : user.id(),
                        Map.of("email", User.normalizeEmail(email == null ? "" : email),
                                "reason", user == null ? "no such user"
                                        : user.isActive() ? "password" : "inactive"));
            } catch (RuntimeException e) {
                // A refusal that cannot be recorded is still a refusal. Never let the audit
                // write turn a clean 401 into a 500 — that difference is itself a signal.
                log.warn("Could not record a refused sign-in for tenant {}", tenantId, e);
            }
        }

        private AuthenticatedPrincipal principalOf(User user) {
            return new AuthenticatedPrincipal(user.id(), user.tenantId(), user.role(),
                    user.branchId());
        }

        /**
         * A syntactically valid stored hash that no password can match.
         *
         * <p>Its only purpose is to give the verifier the same work to do when the address
         * is unknown as when it is known. Built once per call from the hasher itself, so it
         * stays in whatever format the configured algorithm expects.
         */
        private String unmatchableHash() {
            return passwordHasher.hash(UUID.randomUUID().toString());
        }
    }
}
