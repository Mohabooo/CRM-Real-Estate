package com.rescrm.identity.domain;

import com.rescrm.platform.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An outstanding offer of an account, carrying the pre-acceptance half of the user lifecycle.
 *
 * <p>Only the hash of the token is stored. The token is shown once, at issue, and never
 * persisted — a database backup must not yield working invitation links any more than a user
 * table should yield passwords.
 */
@Entity
@Table(name = "invitations")
public class Invitation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    @Column(name = "branch_id", updatable = false)
    private UUID branchId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

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

    protected Invitation() {
        // JPA
    }

    private Invitation(UUID id, UUID tenantId, String email, Role role, UUID branchId,
                       String tokenHash, OffsetDateTime expiresAt) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.email = User.normalizeEmail(email);
        this.role = Objects.requireNonNull(role, "role").name();
        this.branchId = branchId;
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (role.branchScope() != Role.BranchScopeRule.TENANT_WIDE && branchId == null) {
            throw new IllegalArgumentException(
                    "role " + role + " is branch-scoped, so the invitation must name a branch");
        }
    }

    public static Invitation issue(UUID tenantId, String email, Role role, UUID branchId,
                                   String tokenHash, OffsetDateTime expiresAt) {
        return new Invitation(UUID.randomUUID(), tenantId, email, role, branchId, tokenHash,
                expiresAt);
    }

    public InvitationStatus statusAt(Instant now) {
        if (acceptedAt != null) {
            return InvitationStatus.ACCEPTED;
        }
        return expiresAt.toInstant().isAfter(now) ? InvitationStatus.PENDING
                : InvitationStatus.EXPIRED;
    }

    /**
     * Marks the invitation used.
     *
     * <p>Refuses a second acceptance and refuses one past expiry. Both facts are set together
     * because the database requires it: an invitation recorded as accepted by nobody is an
     * account nobody can trace.
     */
    public void accept(UUID userId, OffsetDateTime acceptedAtTime) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(acceptedAtTime, "acceptedAt");
        if (acceptedAt != null) {
            throw new IllegalStateException("Invitation has already been accepted");
        }
        if (!expiresAt.toInstant().isAfter(acceptedAtTime.toInstant())) {
            throw new IllegalStateException("Invitation expired at " + expiresAt);
        }
        this.acceptedAt = acceptedAtTime;
        this.acceptedUserId = userId;
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String email() {
        return email;
    }

    public Role role() {
        return Role.valueOf(role);
    }

    public UUID branchId() {
        return branchId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public OffsetDateTime acceptedAt() {
        return acceptedAt;
    }

    public UUID acceptedUserId() {
        return acceptedUserId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public UUID createdByUserId() {
        return createdByUserId;
    }
}
