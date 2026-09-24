package com.rescrm.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One signed-in browser (doc 28 section 6).
 *
 * <p>The session is the row that turns a cookie into a tenant and a caller. Everything about
 * the authenticated request follows from it, which is why it carries a composite foreign key
 * to {@code (tenant_id, user_id)}: a session naming another tenant's user would not be a
 * broken reference, it would be a cross-tenant authentication.
 *
 * <p>Two expiries, for two different failures. {@link #expiresAt} slides forward as the
 * session is used, so an abandoned session dies on its own. {@link #absoluteExpiresAt} is
 * fixed at issue and never moves, so a session someone keeps warm still ends. With only the
 * sliding one, a stolen cookie exercised once an hour would last forever.
 *
 * <p>The raw token never reaches this class. Only its SHA-256 is stored, so a disclosure of
 * the table yields nothing anyone can present.
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "absolute_expires_at", nullable = false, updatable = false)
    private OffsetDateTime absoluteExpiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    protected Session() {
        // JPA
    }

    private Session(UUID tenantId, UUID userId, String tokenHash, OffsetDateTime issuedAt,
                    Duration idleTimeout, Duration absoluteLifetime) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenHash = requireText(tokenHash, "token hash");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.lastSeenAt = issuedAt;

        requirePositive(idleTimeout, "idle timeout");
        requirePositive(absoluteLifetime, "absolute lifetime");
        if (idleTimeout.compareTo(absoluteLifetime) > 0) {
            throw new IllegalArgumentException(
                    "the idle timeout cannot exceed the absolute lifetime");
        }

        this.absoluteExpiresAt = issuedAt.plus(absoluteLifetime);
        this.expiresAt = issuedAt.plus(idleTimeout);
    }

    public static Session issue(UUID tenantId, UUID userId, String tokenHash,
                                OffsetDateTime issuedAt, Duration idleTimeout,
                                Duration absoluteLifetime) {
        return new Session(tenantId, userId, tokenHash, issuedAt, idleTimeout, absoluteLifetime);
    }

    /**
     * Whether this session may still authenticate a request.
     *
     * <p>Asked of the entity rather than inferred from a field by each caller, because
     * "live" is three conditions and any caller that checks two of them is a bug.
     */
    public boolean isLiveAt(OffsetDateTime at) {
        return revokedAt == null
                && expiresAt.isAfter(at)
                && absoluteExpiresAt.isAfter(at);
    }

    /**
     * Records use and slides the idle expiry forward, never past the absolute ceiling.
     *
     * @return whether anything changed, so a caller can skip a write on a session that was
     *         already touched moments ago
     */
    public boolean touch(OffsetDateTime at, Duration idleTimeout) {
        if (!isLiveAt(at)) {
            return false;
        }
        OffsetDateTime slid = at.plus(idleTimeout);
        OffsetDateTime capped = slid.isAfter(absoluteExpiresAt) ? absoluteExpiresAt : slid;

        boolean changed = false;
        if (at.isAfter(lastSeenAt)) {
            this.lastSeenAt = at;
            changed = true;
        }
        if (capped.isAfter(expiresAt)) {
            this.expiresAt = capped;
            changed = true;
        }
        return changed;
    }

    /**
     * Ends the session now.
     *
     * <p>The reason is required, and the database agrees: signing out, being deactivated and
     * changing a password are different facts, and an audit trail that cannot tell them apart
     * is not much of one. Revoking twice is a no-op so that deactivating an already-signed-out
     * user is not an error.
     */
    public void revoke(OffsetDateTime at, String reason) {
        if (revokedAt != null) {
            return;
        }
        this.revokedAt = Objects.requireNonNull(at, "revokedAt");
        this.revokedReason = requireText(reason, "revocation reason");
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value.trim();
    }

    private static void requirePositive(Duration duration, String what) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(what + " must be a positive duration");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID userId() {
        return userId;
    }

    public OffsetDateTime issuedAt() {
        return issuedAt;
    }

    public OffsetDateTime lastSeenAt() {
        return lastSeenAt;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public OffsetDateTime absoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public String revokedReason() {
        return revokedReason;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
