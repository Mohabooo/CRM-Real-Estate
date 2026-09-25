package com.rescrm.deals.domain;

import com.rescrm.finance.schedule.InstallmentKind;
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
import java.util.Objects;
import java.util.UUID;

/**
 * One expected obligation: what is due, when, and for how much.
 *
 * <p>Nothing here records what was paid. No allocated amount, no paid status, no
 * outstanding figure — those belong to payments and collections in Epic 6, and putting a
 * placeholder for them here would invite somebody to read a zero as an answer.
 *
 * <p>The down payment and the delivery tranche are rows like any other, which is what lets
 * R-PLAN-4 be checked as a single sum over a single table.
 */
@Entity
@Table(name = "installments")
public class Installment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "deal_id", nullable = false, updatable = false)
    private UUID dealId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "expected_amount", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
    private Money expectedAmount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "void_reason")
    private String voidReason;

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

    protected Installment() {
        // JPA
    }

    private Installment(UUID tenantId, UUID dealId, UUID planId, PaymentSchedule.Row row) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.dealId = Objects.requireNonNull(dealId, "dealId");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.sequenceNo = row.sequenceNo();
        this.kind = row.kind().code();
        this.dueDate = row.dueDate();
        this.expectedAmount = row.expectedAmount();
        this.status = InstallmentStatus.PENDING.code();
    }

    /** Persists one generated row unchanged. Nothing is recomputed on the way in. */
    public static Installment from(UUID tenantId, UUID dealId, UUID planId,
                                   PaymentSchedule.Row row) {
        return new Installment(tenantId, dealId, planId, row);
    }

    /** Excluded from every aggregate afterwards (R-SCOPE-1). */
    public void voidWith(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a voided installment records why");
        }
        this.status = InstallmentStatus.VOID.code();
        this.voidReason = reason.trim();
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

    public UUID dealId() {
        return dealId;
    }

    public UUID planId() {
        return planId;
    }

    public int sequenceNo() {
        return sequenceNo;
    }

    public InstallmentKind kind() {
        return InstallmentKind.fromCode(kind);
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public Money expectedAmount() {
        return expectedAmount;
    }

    public InstallmentStatus status() {
        return InstallmentStatus.fromCode(status);
    }

    public String voidReason() {
        return voidReason;
    }
}
