package com.rescrm.deals.domain;

import com.rescrm.finance.schedule.Frequency;
import com.rescrm.finance.schedule.PaymentSchedule;
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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One customer's plan: the terms, copied, and the figures the schedule was built from.
 *
 * <p>Created from a {@link PaymentSchedule} rather than from a template, so the stored
 * figures are the generated ones by construction. Storing the terms and recomputing later
 * would leave two answers to the same question, and the one a customer holds on paper
 * would be whichever was computed first.
 *
 * <p>Doc 22 lists a {@code rounding_policy} column on this table and V7 does not create
 * one. That is a decision, not an oversight: doc 17's conventions fix rounding for the
 * whole system — half-up everywhere except the base installment, which floors so the
 * remainder is non-negative and lands on the last row (R-INST-1, R-INST-2). A per-plan
 * policy column would be a way for one plan to opt out of a rule the financial design
 * depends on, and FIN-010 asserts the floor is not negotiable. If a second policy is ever
 * genuinely needed, it arrives with the rule that justifies it.
 */
@Entity
@Table(name = "customer_payment_plans")
public class CustomerPaymentPlan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "deal_id", nullable = false, updatable = false)
    private UUID dealId;

    /** Provenance. Nothing reads the template through this; the terms above are the copy. */
    @Column(name = "source_template_id")
    private UUID sourceTemplateId;

    @Column(name = "version", nullable = false)
    private int version;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "net_value", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money netValue;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "down_payment_amount", nullable = false,
            columnDefinition = MoneyColumns.AMOUNT)
    private Money downPaymentAmount;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "delivery_payment_amount", nullable = false,
            columnDefinition = MoneyColumns.AMOUNT)
    private Money deliveryPaymentAmount;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "financed_amount", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money financedAmount;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount;

    @Column(name = "frequency", nullable = false)
    private String frequency;

    @Column(name = "first_due_date", nullable = false)
    private LocalDate firstDueDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "superseded_by_id")
    private UUID supersededById;

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

    protected CustomerPaymentPlan() {
        // JPA
    }

    private CustomerPaymentPlan(UUID tenantId, UUID dealId, UUID sourceTemplateId,
                                PaymentSchedule schedule, int installmentCount,
                                Frequency frequency, LocalDate firstDueDate) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.dealId = Objects.requireNonNull(dealId, "dealId");
        this.sourceTemplateId = sourceTemplateId;
        this.version = 1;

        this.netValue = schedule.netValue();
        this.downPaymentAmount = schedule.downPaymentAmount();
        this.deliveryPaymentAmount = schedule.deliveryPaymentAmount();
        this.financedAmount = schedule.financedAmount();
        this.installmentCount = installmentCount;
        this.frequency = frequency.name().toLowerCase(Locale.ROOT);
        this.firstDueDate = Objects.requireNonNull(firstDueDate, "firstDueDate");
        this.status = PlanStatus.DRAFT.code();
    }

    public static CustomerPaymentPlan draftFrom(UUID tenantId, UUID dealId,
                                                UUID sourceTemplateId, PaymentSchedule schedule,
                                                int installmentCount, Frequency frequency,
                                                LocalDate firstDueDate) {
        return new CustomerPaymentPlan(tenantId, dealId, sourceTemplateId, schedule,
                installmentCount, frequency, firstDueDate);
    }

    /**
     * Doc 18 section 5: "draft → draft (regenerate)", allowed while no allocation exists.
     *
     * <p>Restates the figures in place rather than creating a second row, so the plan keeps
     * the identity the deal's screen is already showing, and bumps the version so the
     * regeneration is visible rather than silent. In Epic 5 there are no allocations at all
     * — the columns do not exist — so the precondition reduces to the plan still being a
     * draft, which is what {@link PlanStatus#isRevisable()} answers.
     */
    public void regenerateFrom(UUID newSourceTemplateId, PaymentSchedule schedule,
                               int newInstallmentCount, Frequency newFrequency,
                               LocalDate newFirstDueDate) {
        if (!status().isRevisable()) {
            throw new IllegalStateException(
                    "only a draft plan can be regenerated; this one is " + status().code());
        }
        this.sourceTemplateId = newSourceTemplateId;
        this.netValue = schedule.netValue();
        this.downPaymentAmount = schedule.downPaymentAmount();
        this.deliveryPaymentAmount = schedule.deliveryPaymentAmount();
        this.financedAmount = schedule.financedAmount();
        this.installmentCount = newInstallmentCount;
        this.frequency = newFrequency.name().toLowerCase(Locale.ROOT);
        this.firstDueDate = Objects.requireNonNull(newFirstDueDate, "firstDueDate");
        this.version++;
    }

    /** Doc 18 section 5: "draft → active", by the system, on deal activation. */
    public void activate() {
        transitionTo(PlanStatus.ACTIVE);
        this.status = PlanStatus.ACTIVE.code();
    }

    public void close() {
        transitionTo(PlanStatus.CLOSED);
        this.status = PlanStatus.CLOSED.code();
    }

    public void cancel() {
        transitionTo(PlanStatus.CANCELLED);
        this.status = PlanStatus.CANCELLED.code();
    }

    private void transitionTo(PlanStatus next) {
        PlanStatus current = status();
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("a payment plan cannot move from " + current.code()
                    + " to " + next.code() + "; doc 18 section 5 does not list that transition");
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

    public UUID dealId() {
        return dealId;
    }

    public UUID sourceTemplateId() {
        return sourceTemplateId;
    }

    public int version() {
        return version;
    }

    public Money netValue() {
        return netValue;
    }

    public Money downPaymentAmount() {
        return downPaymentAmount;
    }

    public Money deliveryPaymentAmount() {
        return deliveryPaymentAmount;
    }

    public Money financedAmount() {
        return financedAmount;
    }

    public int installmentCount() {
        return installmentCount;
    }

    public Frequency frequency() {
        return Frequency.valueOf(frequency.toUpperCase(Locale.ROOT));
    }

    public LocalDate firstDueDate() {
        return firstDueDate;
    }

    public PlanStatus status() {
        return PlanStatus.fromCode(status);
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
