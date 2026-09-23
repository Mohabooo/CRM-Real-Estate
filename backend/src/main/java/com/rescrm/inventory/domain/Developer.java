package com.rescrm.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An external party that owns inventory the tenant sells (doc 16, section 6).
 *
 * <p>Exists only for brokered projects. A tenant selling its own stock never creates one, and
 * doc 25 section 3 is explicit that a Developer row standing for the tenant itself would be a
 * self-referential abstraction serving nothing.
 *
 * <p>Deactivated rather than deleted. A developer with historical projects is part of the
 * financial record even after the relationship ends.
 */
@Entity
@Table(name = "developers")
public class Developer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contact", nullable = false)
    private String contact;

    @Column(name = "payment_terms_note")
    private String paymentTermsNote;

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

    protected Developer() {
        // JPA
    }

    private Developer(UUID tenantId, String name, String contact, String paymentTermsNote) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.name = requireText(name, "developer name");
        this.contact = contact == null || contact.isBlank() ? "{}" : contact;
        this.paymentTermsNote = blankToNull(paymentTermsNote);
        this.active = true;
    }

    /** E3-S2: a developer requires a name and nothing else. */
    public static Developer register(UUID tenantId, String name, String contact,
                                     String paymentTermsNote) {
        return new Developer(tenantId, name, contact, paymentTermsNote);
    }

    public void updateDetails(String newName, String newContact, String newPaymentTermsNote) {
        String candidateName = newName != null ? requireText(newName, "developer name") : this.name;
        this.name = candidateName;
        if (newContact != null) {
            this.contact = newContact.isBlank() ? "{}" : newContact;
        }
        if (newPaymentTermsNote != null) {
            this.paymentTermsNote = blankToNull(newPaymentTermsNote);
        }
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

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public String contact() { return contact; }
    public String paymentTermsNote() { return paymentTermsNote; }
    public boolean isActive() { return active; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
    public UUID updatedByUserId() { return updatedByUserId; }
}
