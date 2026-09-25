package com.rescrm.deals.domain;

import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import com.rescrm.platform.money.persistence.MoneyAmountConverter;
import com.rescrm.platform.money.persistence.MoneyColumns;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One concession on a deal, kept individually because R-DISC-2 makes the total a sum.
 *
 * <p>Both the rate and the resolved money are stored. The rate is what somebody agreed;
 * the amount is what it came to against the gross value at the time. Recomputing the
 * second from the first later would silently re-price an old concession if the gross ever
 * moved.
 *
 * <p>Immutable once written: every column but the reason is {@code updatable = false}. A
 * discount is changed by removing it and adding another, which leaves two audit entries
 * instead of one row that quietly became a different number.
 */
@Entity
@Table(name = "deal_discounts")
public class DealDiscount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "deal_id", nullable = false, updatable = false)
    private UUID dealId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "value", nullable = false, updatable = false)
    private BigDecimal value;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "amount", nullable = false, updatable = false,
            columnDefinition = MoneyColumns.AMOUNT)
    private Money amount;

    @Column(name = "reason")
    private String reason;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    protected DealDiscount() {
        // JPA
    }

    private DealDiscount(UUID tenantId, UUID dealId, Concession concession, Money grossValue,
                         String reason) {
        Objects.requireNonNull(concession, "concession");
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.dealId = Objects.requireNonNull(dealId, "dealId");
        this.kind = concession.kind().code();
        this.value = storedValueOf(concession);
        this.amount = concession.amountAgainst(grossValue);
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();

        if (!amount.isPositive()) {
            // A percentage small enough to round to nothing against this gross value. The
            // bounds on the concession itself do not catch it, because whether 0.0001% comes
            // to a piastre depends on the price it is applied to.
            throw new IllegalArgumentException("a discount must come to more than nothing");
        }
    }

    public static DealDiscount of(UUID tenantId, UUID dealId, Concession concession,
                                  Money grossValue, String reason) {
        return new DealDiscount(tenantId, dealId, concession, grossValue, reason);
    }

    /**
     * The number that goes in {@code deal_discounts.value}: the rate for a percentage, the
     * amount for a fixed sum.
     *
     * <p>Private, and the only place a concession is flattened back to a bare number. An
     * accessor returning it would be the shape that lets a rate and an amount be added
     * together, which is the mistake {@link Concession} exists to prevent.
     */
    private static BigDecimal storedValueOf(Concession concession) {
        return switch (concession) {
            case Concession.OfPercent percent -> percent.rate().value();
            case Concession.OfAmount fixed -> fixed.amount().amount();
        };
    }

    public void recordActor(UUID actorUserId) {
        this.createdByUserId = actorUserId;
        this.updatedByUserId = actorUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID dealId() {
        return dealId;
    }

    public DiscountKind kind() {
        return DiscountKind.fromCode(kind);
    }

    /**
     * The concession as it was agreed, rebuilt from the two columns it was stored as.
     *
     * <p>The rate rather than the money, for a percentage: a response that showed only
     * "142,500" would lose the fact that what was agreed was five per cent, and the two
     * stop being the same statement the moment the gross value is anything else.
     */
    public Concession concession() {
        return switch (kind()) {
            case PERCENT -> Concession.ofPercent(Percentage.of(value));
            case FIXED -> Concession.ofAmount(Money.of(value, amount.currency()));
        };
    }

    public Money amount() {
        return amount;
    }

    public String reason() {
        return reason;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
