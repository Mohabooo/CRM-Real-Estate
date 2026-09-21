package com.rescrm.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An organisational office (doc 16, section 2). Drives visibility scoping and reporting.
 *
 * <p>Deactivated rather than deleted. A branch is referenced by users, and later by leads and
 * deals; removing one would orphan history that reporting and commission attribution depend
 * on, which is the same reason doc 16 gives for deactivating users instead of deleting them.
 */
@Entity
@Table(name = "branches")
public class Branch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

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

    protected Branch() {
        // JPA
    }

    private Branch(UUID id, UUID tenantId, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = requireText(name);
        this.active = true;
    }

    public static Branch create(UUID tenantId, String name) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return new Branch(UUID.randomUUID(), tenantId, name);
    }

    public void rename(String newName) {
        this.name = requireText(newName);
    }

    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("branch name must not be blank");
        }
        return value.trim();
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
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
