package com.rescrm.crm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Unqualified demand: a phone number with an interest, possibly a duplicate, possibly never
 * real (doc 16, section 1).
 *
 * <p>The transitions below are doc 18 section 1 and nothing else. A lead never leaves by
 * deletion — {@link #disqualify} and {@link #markConverted} both keep the row, because a
 * funnel you can delete from is a funnel whose conversion rate cannot be audited.
 */
@Entity
@Table(name = "leads")
public class Lead {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "phone_normalized", nullable = false)
    private String phoneNormalized;

    @Column(name = "email")
    private String email;

    @Column(name = "source")
    private String source;

    @Column(name = "stage", nullable = false)
    private String stage;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "next_action_at")
    private OffsetDateTime nextActionAt;

    @Column(name = "first_contact_at")
    private OffsetDateTime firstContactAt;

    @Column(name = "disqualified_reason")
    private String disqualifiedReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interest", nullable = false)
    private String interest;

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

    protected Lead() {
        // JPA
    }

    private Lead(UUID tenantId, String name, String phone, String email, String source,
                 UUID branchId, String interest) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.name = requireText(name, "lead name");
        this.phone = requireText(phone, "phone");
        this.phoneNormalized = PhoneNumbers.normalize(phone);
        this.email = normalizeEmail(email);
        this.source = blankToNull(source);
        this.branchId = branchId;
        this.stage = LeadStage.NEW.code();
        this.status = LeadStatus.ACTIVE.code();
        this.interest = interest == null || interest.isBlank() ? "{}" : interest;
    }

    public static Lead capture(UUID tenantId, String name, String phone, String email,
                               String source, UUID branchId, String interest) {
        return new Lead(tenantId, name, phone, email, source, branchId, interest);
    }

    // ------------------------------------------------------------------ transitions

    /** new to assigned (doc 18). Audited by the caller; the owner must be in this tenant. */
    public void assignTo(UUID newOwnerUserId, UUID newBranchId) {
        Objects.requireNonNull(newOwnerUserId, "ownerUserId");
        requireActive("assign");
        if (stage().requiresOwner()) {
            // Reassignment of an already-assigned lead: the stage does not move backwards,
            // only the owner changes. E2-S2 is explicit that this is a routine operation.
            this.ownerUserId = newOwnerUserId;
            if (newBranchId != null) {
                this.branchId = newBranchId;
            }
            return;
        }
        requireStageTransition(LeadStage.ASSIGNED);
        this.ownerUserId = newOwnerUserId;
        if (newBranchId != null) {
            this.branchId = newBranchId;
        }
        this.stage = LeadStage.ASSIGNED.code();
    }

    /** assigned to contacted. Doc 18 stamps first_contact_at here and only here. */
    public void recordFirstContact(OffsetDateTime contactedAt) {
        requireActive("record contact on");
        requireStageTransition(LeadStage.CONTACTED);
        this.stage = LeadStage.CONTACTED.code();
        this.firstContactAt = Objects.requireNonNull(contactedAt, "contactedAt");
    }

    /** contacted to qualified. Doc 18 requires interest to have been captured. */
    public void qualify() {
        requireActive("qualify");
        if (interest == null || interest.isBlank() || "{}".equals(interest.trim())) {
            throw new IllegalStateException(
                    "Interest must be captured before a lead can be qualified (doc 18 section 1)");
        }
        requireStageTransition(LeadStage.QUALIFIED);
        this.stage = LeadStage.QUALIFIED.code();
    }

    /** Leaves the funnel with a reason, retained (doc 18). */
    public void disqualify(String reason) {
        requireActive("disqualify");
        String trimmed = requireText(reason, "disqualification reason");
        this.status = LeadStatus.DISQUALIFIED.code();
        this.disqualifiedReason = trimmed;
        this.nextActionAt = null;
    }

    /** disqualified back to assigned, with a reason (doc 18). */
    public void reactivate(UUID newOwnerUserId, String reason) {
        if (status() != LeadStatus.DISQUALIFIED) {
            throw new IllegalStateException("Only a disqualified lead can be reactivated");
        }
        requireText(reason, "reactivation reason");
        Objects.requireNonNull(newOwnerUserId, "ownerUserId");
        this.status = LeadStatus.ACTIVE.code();
        this.disqualifiedReason = null;
        this.ownerUserId = newOwnerUserId;
        this.stage = LeadStage.ASSIGNED.code();
    }

    /**
     * Leaves the funnel on conversion, retained (E2-S4).
     *
     * <p>Only a qualified or reserved lead converts. E2-S4 begins "given a qualified lead",
     * and the restriction is not bureaucratic: qualification is where interest is captured and
     * the number is confirmed to be real, so converting before it would put unverified demand
     * into the customer book — which is the one thing the lead/customer split exists to stop.
     */
    public void markConverted() {
        requireActive("convert");
        LeadStage current = stage();
        if (current != LeadStage.QUALIFIED && current != LeadStage.RESERVED) {
            throw new IllegalStateException(
                    "Only a qualified lead can be converted; this one is " + current.code()
                            + " (doc 18 section 1)");
        }
        this.status = LeadStatus.CONVERTED.code();
        this.nextActionAt = null;
    }

    // ------------------------------------------------------------------ attributes

    public void setNextAction(OffsetDateTime at) {
        requireActive("set a next action on");
        this.nextActionAt = at;
    }

    public void captureInterest(String interestJson) {
        requireActive("capture interest on");
        this.interest = interestJson == null || interestJson.isBlank() ? "{}" : interestJson;
    }

    /** Validated in full before anything is assigned, so a rejected edit changes nothing. */
    public void updateContactDetails(String newName, String newPhone, String newEmail) {
        requireActive("edit");

        String candidateName = newName != null ? requireText(newName, "lead name") : this.name;
        String candidatePhone = this.phone;
        String candidateNormalized = this.phoneNormalized;
        if (newPhone != null) {
            candidatePhone = requireText(newPhone, "phone");
            candidateNormalized = PhoneNumbers.normalize(newPhone);
        }
        String candidateEmail = newEmail != null ? normalizeEmail(newEmail) : this.email;

        this.name = candidateName;
        this.phone = candidatePhone;
        this.phoneNormalized = candidateNormalized;
        this.email = candidateEmail;
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private void requireActive(String action) {
        if (!status().isActive()) {
            throw new IllegalStateException(
                    "Cannot " + action + " a lead that is " + status().code());
        }
    }

    private void requireStageTransition(LeadStage next) {
        if (!stage().canTransitionTo(next)) {
            throw new IllegalStateException(
                    "A lead cannot move from " + stage().code() + " to " + next.code()
                            + " (doc 18 section 1)");
        }
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

    static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.indexOf('@') < 1) {
            throw new IllegalArgumentException("email must contain a local part and a domain");
        }
        return normalized;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID branchId() { return branchId; }
    public UUID ownerUserId() { return ownerUserId; }
    public String name() { return name; }
    public String phone() { return phone; }
    public String phoneNormalized() { return phoneNormalized; }
    public String email() { return email; }
    public String source() { return source; }
    public LeadStage stage() { return LeadStage.fromCode(stage); }
    public LeadStatus status() { return LeadStatus.fromCode(status); }
    public OffsetDateTime nextActionAt() { return nextActionAt; }
    public OffsetDateTime firstContactAt() { return firstContactAt; }
    public String disqualifiedReason() { return disqualifiedReason; }
    public String interest() { return interest; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
    public UUID updatedByUserId() { return updatedByUserId; }
}
