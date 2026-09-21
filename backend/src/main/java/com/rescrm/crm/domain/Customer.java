package com.rescrm.crm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A person with an actual commercial relationship (doc 16, section 1).
 *
 * <p>The national identifier is held only as ciphertext, and this class never sees the plain
 * value in either direction: the service encrypts before constructing and decrypts on an
 * explicit, audited read. Keeping the entity ignorant of it means no accidental path — a
 * {@code toString}, a log line, a serialiser — can leak it.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "phone_normalized", nullable = false)
    private String phoneNormalized;

    @Column(name = "email")
    private String email;

    @Column(name = "national_id_enc")
    private String nationalIdCiphertext;

    @Column(name = "address")
    private String address;

    @Column(name = "source_lead_id", updatable = false)
    private UUID sourceLeadId;

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

    protected Customer() {
        // JPA
    }

    private Customer(UUID tenantId, String nameAr, String nameEn, String phone, String email,
                     String address, UUID sourceLeadId, String nationalIdCiphertext) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.nameAr = blankToNull(nameAr);
        this.nameEn = blankToNull(nameEn);
        if (this.nameAr == null && this.nameEn == null) {
            throw new IllegalArgumentException(
                    "a customer needs a name in at least one language");
        }
        this.phone = requireText(phone);
        this.phoneNormalized = PhoneNumbers.normalize(phone);
        this.email = Lead.normalizeEmail(email);
        this.address = blankToNull(address);
        this.sourceLeadId = sourceLeadId;
        this.nationalIdCiphertext = nationalIdCiphertext;
        this.status = CustomerStatus.ACTIVE.code();
    }

    public static Customer create(UUID tenantId, String nameAr, String nameEn, String phone,
                                  String email, String address, String nationalIdCiphertext) {
        return new Customer(tenantId, nameAr, nameEn, phone, email, address, null,
                nationalIdCiphertext);
    }

    /**
     * The conversion constructor (E2-S4): contact details carry across and the records link.
     *
     * <p>The link lives here rather than on the lead, matching doc 22's {@code source_lead_id},
     * and a partial unique index makes a second conversion of the same lead impossible.
     */
    public static Customer fromLead(Lead lead, String nameAr, String nameEn, String address,
                                    String nationalIdCiphertext) {
        Objects.requireNonNull(lead, "lead");
        String english = blankToNull(nameEn) != null ? nameEn : lead.name();
        return new Customer(lead.tenantId(), nameAr, english, lead.phone(), lead.email(),
                address, lead.id(), nationalIdCiphertext);
    }

    /**
     * A null argument leaves that field alone; a blank one clears it, where clearing is legal.
     *
     * <p>Every value is computed and validated before a single field is assigned. A partially
     * applied update would leave the entity in a state it could never have been created in,
     * and inside a persistence context that state is one flush away from being written.
     */
    public void updateDetails(String newNameAr, String newNameEn, String newPhone,
                              String newEmail, String newAddress) {
        String candidateNameAr = newNameAr != null ? blankToNull(newNameAr) : this.nameAr;
        String candidateNameEn = newNameEn != null ? blankToNull(newNameEn) : this.nameEn;
        if (candidateNameAr == null && candidateNameEn == null) {
            throw new IllegalArgumentException("a customer needs a name in at least one language");
        }

        String candidatePhone = this.phone;
        String candidateNormalized = this.phoneNormalized;
        if (newPhone != null) {
            candidatePhone = requireText(newPhone);
            candidateNormalized = PhoneNumbers.normalize(newPhone);
        }

        String candidateEmail = newEmail != null ? Lead.normalizeEmail(newEmail) : this.email;
        String candidateAddress = newAddress != null ? blankToNull(newAddress) : this.address;

        this.nameAr = candidateNameAr;
        this.nameEn = candidateNameEn;
        this.phone = candidatePhone;
        this.phoneNormalized = candidateNormalized;
        this.email = candidateEmail;
        this.address = candidateAddress;
    }

    /** Replaces the stored ciphertext. The plain value never reaches this class. */
    public void replaceNationalIdCiphertext(String ciphertext) {
        this.nationalIdCiphertext = ciphertext;
    }

    public void deactivate() {
        this.status = CustomerStatus.INACTIVE.code();
    }

    public void reactivate() {
        this.status = CustomerStatus.ACTIVE.code();
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("phone must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String nameAr() { return nameAr; }
    public String nameEn() { return nameEn; }
    /** Whichever name is present, English first. For display where one string is needed. */
    public String displayName() { return nameEn != null ? nameEn : nameAr; }
    public String phone() { return phone; }
    public String phoneNormalized() { return phoneNormalized; }
    public String email() { return email; }
    public boolean hasNationalId() { return nationalIdCiphertext != null; }
    public String nationalIdCiphertext() { return nationalIdCiphertext; }
    public String address() { return address; }
    public UUID sourceLeadId() { return sourceLeadId; }
    public CustomerStatus status() { return CustomerStatus.fromCode(status); }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
}
