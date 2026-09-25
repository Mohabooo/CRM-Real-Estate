package com.rescrm.deals.domain;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A sale: one unit, one customer, a price frozen at creation, and a lifecycle.
 *
 * <p>{@code grossValue} is the unit's list price captured when the deal is drafted
 * (R-VAL-1). A later price change on the unit does not reach it, which is the whole point:
 * the customer agreed to a number on a day, and the deal is the record of that.
 *
 * <p>{@code commercialModel} is copied from the project, as an opaque code, and never
 * changes. It governs who collects, which commissions exist and whether payments may be
 * recorded — and never the schedule arithmetic (doc 27 section 2). Nothing in this class
 * branches on it; callers ask a policy.
 *
 * <p>Every state change goes through a named method that checks its own preconditions
 * against {@link DealStatus}. There is no setter for status, because a lifecycle expressed
 * as an assignable field is one that gets assigned.
 */
@Entity
@Table(name = "deals")
public class Deal {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "primary_customer_id", nullable = false)
    private UUID primaryCustomerId;

    @Column(name = "agent_user_id", nullable = false)
    private UUID agentUserId;

    @Column(name = "source_reservation_id", updatable = false)
    private UUID sourceReservationId;

    @Column(name = "deal_date", nullable = false)
    private LocalDate dealDate;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "gross_value", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money grossValue;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "total_discount", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money totalDiscount;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "net_value", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money netValue;

    @Column(name = "commercial_model", nullable = false, updatable = false)
    private String commercialModel;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "discount_approved_at")
    private OffsetDateTime discountApprovedAt;

    @Column(name = "discount_approved_by_user_id")
    private UUID discountApprovedByUserId;

    @Column(name = "down_payment_confirmed_at")
    private LocalDate downPaymentConfirmedAt;

    @Column(name = "down_payment_confirmed_by_user_id")
    private UUID downPaymentConfirmedByUserId;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_reason")
    private String cancelledReason;

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

    protected Deal() {
        // JPA
    }

    private Deal(UUID tenantId, UUID unitId, UUID primaryCustomerId, UUID agentUserId,
                 UUID branchId, UUID sourceReservationId, LocalDate dealDate, Money grossValue,
                 String commercialModel) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.unitId = Objects.requireNonNull(unitId, "unitId");
        this.primaryCustomerId = Objects.requireNonNull(primaryCustomerId, "primaryCustomerId");
        this.agentUserId = Objects.requireNonNull(agentUserId, "agentUserId");
        this.branchId = branchId;
        this.sourceReservationId = sourceReservationId;
        this.dealDate = Objects.requireNonNull(dealDate, "dealDate");
        this.grossValue = Objects.requireNonNull(grossValue, "grossValue");
        this.commercialModel = Objects.requireNonNull(commercialModel, "commercialModel");

        if (!grossValue.isPositive()) {
            throw new IllegalArgumentException("a deal's gross value must be positive");
        }
        this.currency = grossValue.currency().isoCode();
        this.totalDiscount = Money.zero(grossValue.currency());
        this.netValue = grossValue;
        this.status = DealStatus.DRAFT.code();
    }

    /**
     * Doc 18: "— → draft". The shell, with the list price frozen onto it.
     *
     * <p>The caller has already checked that the unit is available or held by this same
     * party, and that the customer exists; those are its preconditions to verify because
     * they need repositories this class must not have.
     */
    public static Deal draft(UUID tenantId, UUID unitId, UUID primaryCustomerId,
                             UUID agentUserId, UUID branchId, UUID sourceReservationId,
                             LocalDate dealDate, Money listPrice, String commercialModel) {
        return new Deal(tenantId, unitId, primaryCustomerId, agentUserId, branchId,
                sourceReservationId, dealDate, listPrice, commercialModel);
    }

    /**
     * Doc 18: "draft → draft (edit)". Re-states the discount total and net value.
     *
     * <p>Takes the total rather than applying one discount at a time, because R-DISC-2 makes
     * the total the sum of every discount against gross — computing it incrementally from
     * whatever this object last held would compound them, which is precisely what that rule
     * forbids.
     */
    public void restateDiscount(Money newTotalDiscount) {
        requireEditable("Discounts");
        Objects.requireNonNull(newTotalDiscount, "newTotalDiscount");

        if (newTotalDiscount.isNegative()) {
            throw new IllegalArgumentException("a discount cannot be negative");
        }
        if (newTotalDiscount.isGreaterThanOrEqualTo(grossValue)) {
            throw new IllegalArgumentException(
                    "the total discount cannot reach the gross value; net value must stay "
                            + "above zero (R-DISC-4)");
        }
        this.totalDiscount = newTotalDiscount;
        this.netValue = grossValue.minus(newTotalDiscount);

        // A changed discount invalidates whatever was approved, because what was approved
        // was a number. Re-approval is cheap; an approval that silently covers a larger
        // concession than the manager saw is not.
        this.discountApprovedAt = null;
        this.discountApprovedByUserId = null;
    }

    /** R-DISC-5. Records who approved the discount that stands on the deal right now. */
    public void approveDiscount(UUID approverUserId, OffsetDateTime at) {
        requireEditable("Discount approval");
        this.discountApprovedByUserId = Objects.requireNonNull(approverUserId, "approver");
        this.discountApprovedAt = Objects.requireNonNull(at, "at");
    }

    public boolean isDiscountApproved() {
        return discountApprovedAt != null;
    }

    /**
     * Doc 18: "draft → active".
     *
     * <p>The table's four preconditions are checked in two places by necessity. A plan
     * exists and R-PLAN-4 holds, and the unit is not sold elsewhere, all need repositories,
     * so the service checks them. What this method owns is that the deal is in draft and
     * that an approval, where one was required, is present.
     */
    public void activate(boolean approvalRequired, OffsetDateTime at) {
        transitionTo(DealStatus.ACTIVE);
        if (approvalRequired && !isDiscountApproved()) {
            throw new IllegalStateException(
                    "this discount needs manager approval before the deal can be activated "
                            + "(R-DISC-5)");
        }
        this.status = DealStatus.ACTIVE.code();
        this.activatedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * Doc 18: "active → down payment confirmed", brokered only.
     *
     * <p>A date and a confirming user, and no amount (R-DP-2). It is a fact the tenant
     * learns from the developer, not a ledger entry — which is why it lives on the deal and
     * creates no payment.
     */
    public void confirmDownPaymentReceivedByDeveloper(LocalDate on, UUID confirmingUserId) {
        if (status() != DealStatus.ACTIVE) {
            throw new IllegalStateException(
                    "a down payment can only be confirmed on an active deal; this one is "
                            + status().code());
        }
        this.downPaymentConfirmedAt = Objects.requireNonNull(on, "on");
        this.downPaymentConfirmedByUserId = Objects.requireNonNull(confirmingUserId, "user");
    }

    public boolean isDownPaymentConfirmedByDeveloper() {
        return downPaymentConfirmedAt != null;
    }

    /** Doc 18: "active → completed". */
    public void complete(OffsetDateTime at) {
        transitionTo(DealStatus.COMPLETED);
        this.status = DealStatus.COMPLETED.code();
        this.completedAt = Objects.requireNonNull(at, "at");
    }

    /** Doc 18: "draft → cancelled" and, in P2, "active → cancelled". */
    public void cancel(OffsetDateTime at, String reason) {
        transitionTo(DealStatus.CANCELLED);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a cancelled deal records why");
        }
        this.status = DealStatus.CANCELLED.code();
        this.cancelledAt = Objects.requireNonNull(at, "at");
        this.cancelledReason = reason.trim();
    }

    private void transitionTo(DealStatus next) {
        DealStatus current = status();
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("a deal cannot move from " + current.code()
                    + " to " + next.code() + "; doc 18 section 4 does not list that "
                    + "transition");
        }
    }

    private void requireEditable(String what) {
        if (!status().isEditable()) {
            throw new IllegalStateException(
                    what + " can only be changed while the deal is a draft; this one is "
                            + status().code());
        }
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

    public UUID branchId() {
        return branchId;
    }

    public UUID unitId() {
        return unitId;
    }

    public UUID primaryCustomerId() {
        return primaryCustomerId;
    }

    public UUID agentUserId() {
        return agentUserId;
    }

    public UUID sourceReservationId() {
        return sourceReservationId;
    }

    public LocalDate dealDate() {
        return dealDate;
    }

    public Money grossValue() {
        return grossValue;
    }

    public Money totalDiscount() {
        return totalDiscount;
    }

    public Money netValue() {
        return netValue;
    }

    public String commercialModel() {
        return commercialModel;
    }

    public String currency() {
        return currency;
    }

    public DealStatus status() {
        return DealStatus.fromCode(status);
    }

    public OffsetDateTime discountApprovedAt() {
        return discountApprovedAt;
    }

    public UUID discountApprovedByUserId() {
        return discountApprovedByUserId;
    }

    public LocalDate downPaymentConfirmedAt() {
        return downPaymentConfirmedAt;
    }

    public UUID downPaymentConfirmedByUserId() {
        return downPaymentConfirmedByUserId;
    }

    public OffsetDateTime activatedAt() {
        return activatedAt;
    }

    public OffsetDateTime completedAt() {
        return completedAt;
    }

    public OffsetDateTime cancelledAt() {
        return cancelledAt;
    }

    public String cancelledReason() {
        return cancelledReason;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
