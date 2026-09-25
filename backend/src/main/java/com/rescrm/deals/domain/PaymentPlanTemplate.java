package com.rescrm.deals.domain;

import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.finance.schedule.Frequency;
import com.rescrm.finance.schedule.PlanTerms;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import com.rescrm.platform.money.persistence.MoneyColumns;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.rescrm.platform.money.persistence.PercentageConverter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A reusable plan shape (E5-S3), stated the way a price list states one.
 *
 * <p>A template is not a schedule and never becomes one. {@link #termsFor} produces the
 * terms for a particular deal; the resulting plan copies them. That copy is what makes
 * TPL-001 — a template edit never reaching a live customer's schedule — structural rather
 * than a rule somebody has to remember.
 */
@Entity
@Table(name = "payment_plan_templates")
public class PaymentPlanTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    /** "10% down, 8 years quarterly, 5% on delivery" — what an agent recognises. */
    @Column(name = "shorthand_label")
    private String shorthandLabel;

    @Column(name = "down_payment_type", nullable = false)
    private String downPaymentType;

    @Column(name = "down_payment_value", nullable = false)
    private BigDecimal downPaymentValue;

    @Convert(converter = PercentageConverter.class)
    @Column(name = "delivery_payment_percent", nullable = false,
            columnDefinition = MoneyColumns.RATE)
    private Percentage deliveryPaymentPercent;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount;

    @Column(name = "frequency", nullable = false)
    private String frequency;

    @Column(name = "first_installment_offset_days")
    private Integer firstInstallmentOffsetDays;

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

    protected PaymentPlanTemplate() {
        // JPA
    }

    private PaymentPlanTemplate(UUID tenantId, UUID projectId, String name,
                                String shorthandLabel, Shape shape) {
        Objects.requireNonNull(shape, "shape");
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.projectId = projectId;
        this.name = requireText(name, "template name");
        this.shorthandLabel = blankToNull(shorthandLabel);
        this.downPaymentType = typeOf(shape.downPayment()).code();
        this.downPaymentValue = storedValueOf(shape.downPayment());
        this.deliveryPaymentPercent = shape.deliveryPercent();
        this.installmentCount = shape.installmentCount();
        this.frequency = shape.frequency().name().toLowerCase(Locale.ROOT);
        this.firstInstallmentOffsetDays = shape.firstInstallmentOffsetDays();
        this.active = true;

        if (shape.installmentCount() < 1) {
            throw new IllegalArgumentException("a template has at least one installment");
        }
        if (shape.deliveryPercent().exceedsWhole()) {
            throw new IllegalArgumentException(
                    "the delivery payment cannot exceed the whole net value");
        }
        if (shape.firstInstallmentOffsetDays() != null
                && shape.firstInstallmentOffsetDays() < 0) {
            throw new IllegalArgumentException(
                    "the first installment cannot fall before the deal date");
        }
    }

    /**
     * The shape of a template, separate from the entity so construction stays readable.
     *
     * <p>{@code downPayment} is finance's own sealed type rather than a kind plus a loose
     * number, so "10% down" and "500,000 down" stay distinguishable all the way from the
     * request to the generator, and a template cannot be built with a percentage in the
     * amount slot.
     *
     * @param firstInstallmentOffsetDays null means one frequency interval (doc 17 section 12)
     */
    public record Shape(DownPayment downPayment, Percentage deliveryPercent,
                        int installmentCount, Frequency frequency,
                        Integer firstInstallmentOffsetDays) {
    }

    public static PaymentPlanTemplate create(UUID tenantId, UUID projectId, String name,
                                             String shorthandLabel, Shape shape) {
        return new PaymentPlanTemplate(tenantId, projectId, name, shorthandLabel, shape);
    }

    /** E5-S3: a template's terms may be corrected while it is still what somebody offers. */
    public void restate(Shape shape) {
        Objects.requireNonNull(shape, "shape");
        this.downPaymentType = typeOf(shape.downPayment()).code();
        this.downPaymentValue = storedValueOf(shape.downPayment());
        this.deliveryPaymentPercent = shape.deliveryPercent();
        this.installmentCount = shape.installmentCount();
        this.frequency = shape.frequency().name().toLowerCase(Locale.ROOT);
        this.firstInstallmentOffsetDays = shape.firstInstallmentOffsetDays();
    }

    /**
     * The terms this template produces for one deal.
     *
     * <p>The offset column is passed through exactly as it stands, including when it is
     * null. Null is not "zero" and it is not "ninety": doc 17 section 12 gives the default
     * as one frequency INTERVAL, and the generator honours that as month arithmetic so the
     * schedule keeps its original day-of-month (R-INST-6). Translating the default into a
     * day count here — thirty days to the month — moved a quarterly plan struck on 31
     * January onto the 1st of every month for eight years, which looks right until somebody
     * compares it with the contract.
     */
    public PlanTerms termsFor(Money netValue, LocalDate dealDate,
                              Optional<LocalDate> projectDeliveryDate) {
        return new PlanTerms(netValue, downPaymentIn(netValue.currency()), deliveryPaymentPercent,
                installmentCount, frequency(), dealDate,
                Optional.ofNullable(firstInstallmentOffsetDays), projectDeliveryDate);
    }

    /**
     * The down payment this template states.
     *
     * <p>Takes the currency because a fixed down payment is an amount and an amount needs
     * one. The column does not store it: doc 17 N9 fixes the system on EGP, and a template
     * denominated differently from the deal it is applied to would be a silent currency
     * mismatch rather than a feature.
     */
    public DownPayment downPaymentIn(CurrencyCode currency) {
        return downPaymentType() == DiscountKind.PERCENT
                ? DownPayment.percent(Percentage.of(downPaymentValue))
                : DownPayment.fixed(Money.of(downPaymentValue, currency));
    }

    /** Archiving leaves live instances untouched (TPL-002): they copied their terms. */
    public void archive() {
        this.active = false;
    }

    public void restore() {
        this.active = true;
    }

    public void rename(String newName, String newShorthand) {
        this.name = requireText(newName, "template name");
        this.shorthandLabel = blankToNull(newShorthand);
    }

    public void recordActor(UUID actorUserId, boolean creating) {
        if (creating) {
            this.createdByUserId = actorUserId;
        }
        this.updatedByUserId = actorUserId;
    }

    private static DiscountKind typeOf(DownPayment downPayment) {
        return downPayment instanceof DownPayment.OfPercent
                ? DiscountKind.PERCENT
                : DiscountKind.FIXED;
    }

    /**
     * The number that goes in {@code down_payment_value}: the rate, or the amount.
     *
     * <p>Private for the same reason as its counterpart on {@code DealDiscount} — this is
     * the one place the distinction is flattened, and it is flattened on its way to a
     * column rather than into anybody's arithmetic.
     */
    private static BigDecimal storedValueOf(DownPayment downPayment) {
        return switch (downPayment) {
            case DownPayment.OfPercent percent -> percent.percentage().value();
            case DownPayment.OfAmount fixed -> fixed.amount().amount();
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value.trim();
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID projectId() {
        return projectId;
    }

    public String name() {
        return name;
    }

    public String shorthandLabel() {
        return shorthandLabel;
    }

    public DiscountKind downPaymentType() {
        return DiscountKind.fromCode(downPaymentType);
    }

    public Percentage deliveryPaymentPercent() {
        return deliveryPaymentPercent;
    }

    public int installmentCount() {
        return installmentCount;
    }

    public Frequency frequency() {
        return Frequency.valueOf(frequency.toUpperCase(Locale.ROOT));
    }

    public Integer firstInstallmentOffsetDays() {
        return firstInstallmentOffsetDays;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
