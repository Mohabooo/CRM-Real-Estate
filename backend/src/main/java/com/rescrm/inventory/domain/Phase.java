package com.rescrm.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A sub-grouping within a project — a building, a zone, a launch batch (doc 16, section 8).
 *
 * <p>Deliberately thin. Doc 16 calls it thin and means it: a phase is a label with a delivery
 * date. Giving it a status, a price list or a lifecycle would make it a second project, and
 * the schema would then have two places to ask what is for sale.
 */
@Entity
@Table(name = "phases")
public class Phase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

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

    protected Phase() {
        // JPA
    }

    private Phase(UUID tenantId, UUID projectId, String name, LocalDate deliveryDate) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.name = requireText(name);
        this.deliveryDate = deliveryDate;
    }

    public static Phase create(UUID tenantId, UUID projectId, String name,
                               LocalDate deliveryDate) {
        return new Phase(tenantId, projectId, name, deliveryDate);
    }

    public void updateDetails(String newName, LocalDate newDeliveryDate) {
        if (newName != null) {
            this.name = requireText(newName);
        }
        if (newDeliveryDate != null) {
            this.deliveryDate = newDeliveryDate;
        }
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("phase name must not be blank");
        }
        return value.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID projectId() { return projectId; }
    public String name() { return name; }
    public LocalDate deliveryDate() { return deliveryDate; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
    public UUID updatedByUserId() { return updatedByUserId; }
}
