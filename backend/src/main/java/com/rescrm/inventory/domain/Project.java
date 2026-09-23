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
 * A development containing sellable units, and the row that carries the commercial model
 * (doc 16 section 7, doc 25 section 3).
 *
 * <p>The model is held as an opaque code, never as the {@code CommercialModel} enum. That is
 * the containment rule of doc 28 section 3 doing its job at the point it matters most: this
 * class is where a {@code switch} on the model would be most tempting and most damaging. The
 * code goes to a policy and answers come back.
 *
 * <p>The model is also immutable. There is no setter and no transition for it, because
 * changing it after deals exist would retroactively alter the meaning of financial records
 * already written — doc 25 section 3 puts that behind an audited migration that is out of MVP
 * scope, and the safest expression of "out of scope" is "no method exists".
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "commercial_model", nullable = false, updatable = false)
    private String commercialModel;

    @Column(name = "developer_id", updatable = false)
    private UUID developerId;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "location")
    private String location;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "status", nullable = false)
    private String status;

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

    protected Project() {
        // JPA
    }

    private Project(UUID tenantId, String commercialModel, UUID developerId, String nameAr,
                    String nameEn, String location, LocalDate deliveryDate) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.commercialModel = Objects.requireNonNull(commercialModel, "commercialModel");
        this.developerId = developerId;
        this.nameAr = blankToNull(nameAr);
        this.nameEn = blankToNull(nameEn);
        if (this.nameAr == null && this.nameEn == null) {
            throw new IllegalArgumentException("a project needs a name in at least one language");
        }
        this.location = blankToNull(location);
        this.deliveryDate = deliveryDate;
        this.status = ProjectStatus.DRAFT.code();
    }

    /**
     * E3-S1. The caller has already asked the ownership policy whether the developer is
     * right for the model; this constructor does not second-guess it, because the policy is
     * the single authority and duplicating its rule here would create two.
     */
    public static Project create(UUID tenantId, String commercialModelCode, UUID developerId,
                                 String nameAr, String nameEn, String location,
                                 LocalDate deliveryDate) {
        return new Project(tenantId, commercialModelCode, developerId, nameAr, nameEn, location,
                deliveryDate);
    }

    /** Validated in full before anything is assigned, so a rejected edit changes nothing. */
    public void updateDetails(String newNameAr, String newNameEn, String newLocation,
                              LocalDate newDeliveryDate) {
        String candidateNameAr = newNameAr != null ? blankToNull(newNameAr) : this.nameAr;
        String candidateNameEn = newNameEn != null ? blankToNull(newNameEn) : this.nameEn;
        if (candidateNameAr == null && candidateNameEn == null) {
            throw new IllegalArgumentException("a project needs a name in at least one language");
        }

        this.nameAr = candidateNameAr;
        this.nameEn = candidateNameEn;
        if (newLocation != null) {
            this.location = blankToNull(newLocation);
        }
        if (newDeliveryDate != null) {
            this.deliveryDate = newDeliveryDate;
        }
    }

    public void changeStatus(ProjectStatus next) {
        ProjectStatus current = status();
        if (current == next) {
            return;
        }
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("A project cannot move from " + current.code()
                    + " to " + next.code());
        }
        this.status = next.code();
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }

    /** The opaque model code. Resolve it through a policy; never branch on it here. */
    public String commercialModel() { return commercialModel; }

    public UUID developerId() { return developerId; }
    public String nameAr() { return nameAr; }
    public String nameEn() { return nameEn; }

    /** English where there is one, Arabic otherwise. */
    public String displayName() { return nameEn != null ? nameEn : nameAr; }

    public String location() { return location; }
    public LocalDate deliveryDate() { return deliveryDate; }
    public ProjectStatus status() { return ProjectStatus.fromCode(status); }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
    public UUID updatedByUserId() { return updatedByUserId; }
}
