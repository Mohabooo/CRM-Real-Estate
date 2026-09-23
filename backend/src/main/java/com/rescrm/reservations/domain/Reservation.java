package com.rescrm.reservations.domain;

import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.persistence.MoneyAmountConverter;
import com.rescrm.platform.money.persistence.MoneyColumns;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * A time-bound hold on a unit (doc 16 section 10, doc 18 section 3).
 *
 * <p>The deposit is {@link Money}, and what it means is not this class's business. Under
 * own-inventory it is cash the tenant holds; under brokered inventory it is a confirmation
 * the developer received it. Same amount, different fact, and {@code CollectionPolicy}
 * decides which — so this entity records the number and never interprets it.
 *
 * <p>{@code originalExpiresAt} is kept alongside {@code expiresAt} so that "extend within a
 * limit" has a fixed point to measure from. Without it a manager could walk a hold forward a
 * day at a time indefinitely and never exceed any limit.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "lead_id", updatable = false)
    private UUID leadId;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "agent_user_id", nullable = false)
    private UUID agentUserId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "reserved_at", nullable = false, updatable = false)
    private OffsetDateTime reservedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "original_expires_at", nullable = false, updatable = false)
    private OffsetDateTime originalExpiresAt;

    @Column(name = "extension_count", nullable = false)
    private int extensionCount;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "deposit_amount", columnDefinition = MoneyColumns.AMOUNT)
    private Money depositAmount;

    @Column(name = "deposit_received", nullable = false)
    private boolean depositReceived;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "closed_reason")
    private String closedReason;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

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

    protected Reservation() {
        // JPA
    }

    private Reservation(UUID tenantId, UUID unitId, UUID leadId, UUID customerId,
                        UUID agentUserId, UUID branchId, OffsetDateTime reservedAt,
                        OffsetDateTime expiresAt) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.unitId = Objects.requireNonNull(unitId, "unitId");
        this.agentUserId = Objects.requireNonNull(agentUserId, "agentUserId");

        if ((leadId == null) == (customerId == null)) {
            throw new IllegalArgumentException(
                    "a reservation is held for exactly one of a lead or a customer");
        }
        this.leadId = leadId;
        this.customerId = customerId;
        this.branchId = branchId;

        this.reservedAt = Objects.requireNonNull(reservedAt, "reservedAt");
        if (expiresAt == null || !expiresAt.isAfter(reservedAt)) {
            throw new IllegalArgumentException("a hold must expire after it starts");
        }
        this.expiresAt = expiresAt;
        this.originalExpiresAt = expiresAt;
        this.extensionCount = 0;
        this.depositReceived = false;
        this.status = ReservationStatus.PENDING.code();
    }

    /** E4-S1. Starts pending: the unit is spoken for but not yet withheld. */
    public static Reservation place(UUID tenantId, UUID unitId, UUID leadId, UUID customerId,
                                    UUID agentUserId, UUID branchId, OffsetDateTime reservedAt,
                                    OffsetDateTime expiresAt) {
        return new Reservation(tenantId, unitId, leadId, customerId, agentUserId, branchId,
                reservedAt, expiresAt);
    }

    // ------------------------------------------------------------------ transitions

    /** E4-S1: the unit becomes reserved only here, never on placement. */
    public void confirm(OffsetDateTime now) {
        requireLive(now, "confirm");
        requireTransition(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.CONFIRMED.code();
    }

    /** Doc 18: a release needs a reason; the unit goes back to inventory. */
    public void release(String reason, OffsetDateTime now) {
        String trimmed = requireText(reason, "release reason");
        requireTransition(ReservationStatus.RELEASED);
        this.status = ReservationStatus.RELEASED.code();
        this.closedReason = trimmed;
        this.closedAt = now;
    }

    /** Withdrawn before confirmation, so no unit was ever withheld. */
    public void cancel(String reason, OffsetDateTime now) {
        requireTransition(ReservationStatus.CANCELLED);
        this.status = ReservationStatus.CANCELLED.code();
        this.closedReason = reason == null || reason.isBlank() ? null : reason.trim();
        this.closedAt = now;
    }

    /**
     * E4-S2. The system's doing: no actor, and it only applies once the time has actually
     * passed. Expiring a hold early would take a unit off an agent who still had it.
     */
    public void expire(OffsetDateTime now) {
        if (!hasExpiredBy(now)) {
            throw new IllegalStateException("This hold does not expire until " + expiresAt);
        }
        requireTransition(ReservationStatus.EXPIRED);
        this.status = ReservationStatus.EXPIRED.code();
        this.closedReason = "Expired at " + expiresAt;
        this.closedAt = now;
    }

    /**
     * E4-S3. Moves the expiry out; the limit itself is the service's to enforce, because it
     * comes from tenant settings rather than from anything this row knows.
     */
    public void extendTo(OffsetDateTime newExpiry, OffsetDateTime now) {
        requireLive(now, "extend");
        if (newExpiry == null || !newExpiry.isAfter(expiresAt)) {
            throw new IllegalArgumentException("an extension must move the expiry later");
        }
        this.expiresAt = newExpiry;
        this.extensionCount++;
    }

    /** E4-S4. The amount only; what it means belongs to the commercial model. */
    public void recordDeposit(Money amount, boolean received) {
        if (amount != null && !amount.isPositive()) {
            throw new IllegalArgumentException("a deposit must be greater than zero");
        }
        this.depositAmount = amount;
        this.depositReceived = amount != null && received;
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    // ------------------------------------------------------------------ queries

    public boolean hasExpiredBy(OffsetDateTime now) {
        return status().isActive() && !now.isBefore(expiresAt);
    }

    /** How far this hold has already been pushed beyond where it originally ended. */
    public Duration extensionSoFar() {
        return Duration.between(originalExpiresAt, expiresAt);
    }

    // ------------------------------------------------------------------ internals

    private void requireLive(OffsetDateTime now, String action) {
        if (!status().isActive()) {
            throw new IllegalStateException(
                    "Cannot " + action + " a hold that is " + status().code());
        }
        if (hasExpiredBy(now)) {
            throw new IllegalStateException(
                    "Cannot " + action + " a hold that expired at " + expiresAt);
        }
    }

    private void requireTransition(ReservationStatus next) {
        ReservationStatus current = status();
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("A reservation cannot move from " + current.code()
                    + " to " + next.code() + " (doc 18 section 3)");
        }
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID unitId() { return unitId; }
    public UUID leadId() { return leadId; }
    public UUID customerId() { return customerId; }
    public UUID agentUserId() { return agentUserId; }
    public UUID branchId() { return branchId; }
    public OffsetDateTime reservedAt() { return reservedAt; }
    public OffsetDateTime expiresAt() { return expiresAt; }
    public OffsetDateTime originalExpiresAt() { return originalExpiresAt; }
    public int extensionCount() { return extensionCount; }
    public Money depositAmount() { return depositAmount; }
    public boolean isDepositReceived() { return depositReceived; }
    public ReservationStatus status() { return ReservationStatus.fromCode(status); }
    public String closedReason() { return closedReason; }
    public OffsetDateTime closedAt() { return closedAt; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public UUID createdByUserId() { return createdByUserId; }
    public UUID updatedByUserId() { return updatedByUserId; }
}
