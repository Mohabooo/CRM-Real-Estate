package com.rescrm.identity.domain;

import com.rescrm.commercialmodel.CommercialModelCodes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The company using the system (doc 16, section 1).
 *
 * <p>The isolation root: it carries no {@code tenant_id} because its identity is the tenant.
 * Every other row in the system reaches it directly or transitively, and its row-level
 * security policy matches on {@code id} rather than {@code tenant_id}.
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false)
    private String status;

    /**
     * Held as an opaque code, never as {@code CommercialModel}.
     *
     * <p>Doc 28 section 2 rule 5 keeps the enum inside its own package so no module can branch
     * on it. Validation goes through {@link CommercialModelCodes}, which is the single
     * authority on which codes exist.
     */
    @Column(name = "default_commercial_model", nullable = false)
    private String defaultCommercialModel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false)
    private String settings;

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

    protected Tenant() {
        // JPA
    }

    private Tenant(UUID id, String name, String defaultCommercialModel, String settings) {
        this.id = id;
        this.name = requireText(name, "tenant name");
        this.status = TenantStatus.PROVISIONING.code();
        this.defaultCommercialModel = CommercialModelCodes.requireValid(defaultCommercialModel);
        this.settings = settings == null || settings.isBlank() ? "{}" : settings;
    }

    /** A new tenant always starts in {@code provisioning} — never directly active. */
    public static Tenant provision(String name, String defaultCommercialModel, String settings) {
        return new Tenant(UUID.randomUUID(), name, defaultCommercialModel, settings);
    }

    public void transitionTo(TenantStatus next) {
        TenantStatus current = status();
        if (current == next) {
            return;
        }
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Tenant cannot move from " + current + " to " + next
                            + "; permitted next states are limited by doc 16 section 1");
        }
        this.status = next.code();
    }

    public void rename(String newName) {
        this.name = requireText(newName, "tenant name");
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

    public String name() {
        return name;
    }

    public TenantStatus status() {
        return TenantStatus.fromCode(status);
    }

    public String defaultCommercialModel() {
        return defaultCommercialModel;
    }

    public String settings() {
        return settings;
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
