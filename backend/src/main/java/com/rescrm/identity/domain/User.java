package com.rescrm.identity.domain;

import com.rescrm.platform.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A person who logs in (doc 16, section 3).
 *
 * <p>The {@code invited} state of that section's lifecycle lives in {@link Invitation}, not
 * here: a user row exists from the moment the person can authenticate, which is why
 * {@code password_hash} can be non-null. {@code is_active} carries the remaining two states,
 * matching the column doc 22 specifies.
 *
 * <p>Never deleted. Doc 16: "deactivation never orphans financial records" — a user is the
 * agent of record on deals and the payee on outbound commissions, so removing the row would
 * destroy attribution the money depends on.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone")
    private String phone;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

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

    protected User() {
        // JPA
    }

    private User(UUID id, UUID tenantId, String email, String passwordHash, String name,
                 String phone, Role role, UUID branchId) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.email = normalizeEmail(email);
        this.passwordHash = requireText(passwordHash, "password hash");
        this.name = requireText(name, "user name");
        this.phone = phone == null || phone.isBlank() ? null : phone.trim();
        this.role = Objects.requireNonNull(role, "role").name();
        this.branchId = requireBranchConsistentWith(role, branchId);
        this.active = true;
    }

    public static User create(UUID tenantId, String email, String passwordHash, String name,
                              String phone, Role role, UUID branchId) {
        return new User(UUID.randomUUID(), tenantId, email, passwordHash, name, phone, role,
                branchId);
    }

    /**
     * Normalised to lower case and trimmed, matching the database check constraint.
     *
     * <p>Done here rather than only at the boundary so that uniqueness per tenant means what a
     * person expects: {@code Ali@example.com} and {@code ali@example.com} are one account, not
     * two.
     */
    public static String normalizeEmail(String email) {
        String normalized = requireText(email, "email").toLowerCase(Locale.ROOT);
        if (normalized.indexOf('@') < 1) {
            throw new IllegalArgumentException("email must contain a local part and a domain");
        }
        return normalized;
    }

    private static UUID requireBranchConsistentWith(Role role, UUID branchId) {
        if (role.branchScope() != Role.BranchScopeRule.TENANT_WIDE && branchId == null) {
            throw new IllegalArgumentException(
                    "role " + role + " is branch-scoped and requires a branch");
        }
        return branchId;
    }

    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    public void changeRole(Role newRole, UUID newBranchId) {
        this.role = Objects.requireNonNull(newRole, "role").name();
        this.branchId = requireBranchConsistentWith(newRole, newBranchId);
    }

    public void rename(String newName) {
        this.name = requireText(newName, "user name");
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = requireText(newHash, "password hash");
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

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String name() {
        return name;
    }

    public String phone() {
        return phone;
    }

    public Role role() {
        return Role.valueOf(role);
    }

    public UUID branchId() {
        return branchId;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public UUID createdByUserId() {
        return createdByUserId;
    }

    public UUID updatedByUserId() {
        return updatedByUserId;
    }
}
