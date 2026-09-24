package com.rescrm.platform.tenancy;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

/**
 * The two lookups that must happen before a tenant exists to look anything up under.
 *
 * <p>Authentication has a bootstrapping problem, the same one the expiry sweep has and in the
 * same shape. Row-level security needs {@code app.current_tenant_id} set before any row is
 * visible. But a login request carries a company slug and nothing else, and an authenticated
 * request carries a cookie and nothing else — in both cases the tenant is precisely what the
 * query is trying to find out.
 *
 * <p>V6 answers it the way V5 did: two narrow, named, SELECT-only policies that apply when
 * {@code app.platform_task} is {@code 'authentication'}. They cover the tenant registry and
 * the session table and nothing else, they grant no write anywhere, and
 * {@link TenantAwareDataSource} clears the flag on every connection it hands out so no
 * request can inherit it from the pool. The policies narrow further than the flag does: only
 * ACTIVE tenants and only live sessions are visible here, so a suspended tenant cannot be
 * signed into and a revoked session cannot be resurrected even by a bug above this class.
 *
 * <p>Both methods return ids and nothing else, exactly like {@link PlatformTenantRegistry}.
 * Whatever the caller does next happens under {@code TenantContext}, through the ordinary
 * policy, like every other read in the system.
 */
@Component
public class PlatformAuthenticationLookup {

    /** Matches the policies in V6 exactly; any other value grants nothing. */
    static final String PLATFORM_TASK = "authentication";

    private final JdbcTemplate jdbc;

    public PlatformAuthenticationLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a cookie resolves to, before anything is loaded properly. */
    public record SessionIdentity(UUID sessionId, UUID tenantId, UUID userId) {
    }

    /** What an invitation token resolves to, before anything is loaded properly. */
    public record InvitationIdentity(UUID invitationId, UUID tenantId) {
    }

    /** The tenant a company slug names, if it exists and is active. */
    public Optional<UUID> activeTenantBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return underAuthenticationTask(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM tenants WHERE slug = ?")) {
                statement.setString(1, slug);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next()
                            ? Optional.of(rs.getObject(1, UUID.class))
                            : Optional.<UUID>empty();
                }
            }
        });
    }

    /**
     * The session a cookie hash names, if it is live.
     *
     * <p>Takes no tenant id, and does not need one: the row that comes back is what NAMES the
     * tenant rather than something a tenant selected. The token behind the hash is 256 bits
     * of {@link java.security.SecureRandom}, so this cannot be walked.
     */
    public Optional<SessionIdentity> liveSessionByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return underAuthenticationTask(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, tenant_id, user_id FROM sessions WHERE token_hash = ?")) {
                statement.setString(1, tokenHash);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new SessionIdentity(
                                    rs.getObject(1, UUID.class),
                                    rs.getObject(2, UUID.class),
                                    rs.getObject(3, UUID.class)))
                            : Optional.<SessionIdentity>empty();
                }
            }
        });
    }

    /**
     * The invitation a token hash names, if it is still pending.
     *
     * <p>The same shape as the session lookup and for the same reason: the token is what
     * names the tenant, so there is no tenant to scope the query by. The policy behind it
     * hides accepted and expired invitations, so an exhausted token is as invisible here as
     * an invented one — which is the answer the service gives anyway.
     */
    public Optional<InvitationIdentity> pendingInvitationByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return underAuthenticationTask(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, tenant_id FROM invitations WHERE token_hash = ?")) {
                statement.setString(1, tokenHash);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new InvitationIdentity(
                                    rs.getObject(1, UUID.class),
                                    rs.getObject(2, UUID.class)))
                            : Optional.<InvitationIdentity>empty();
                }
            }
        });
    }

    /**
     * Sets the flag and runs the body on one connection, then always clears it.
     *
     * <p>Plain {@code SET}, not {@code SET LOCAL}. {@code SET LOCAL} outside a transaction
     * block emits a warning and has no effect at all, and authentication runs before any
     * transaction is open — so the query would see an empty flag, match no policy and find
     * nothing, while passing every test that connects as a superuser and bypasses row-level
     * security. That exact mistake cost the reservation expiry sweep its entire function
     * until it was caught; it is not repeated here.
     */
    private <T> T underAuthenticationTask(ConnectionCallback<T> body) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            try (Statement flag = connection.createStatement()) {
                flag.execute("SET app.platform_task = '" + PLATFORM_TASK + "'");
            }
            try {
                return body.doInConnection(connection);
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("SET app.platform_task = ''");
                }
            }
        });
    }
}
