package com.rescrm.deals.api;

import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.CustomerPaymentPlan;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.DealDiscount;
import com.rescrm.deals.domain.Installment;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.service.PlanWithSchedule;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.finance.schedule.Frequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Request and response shapes for {@code /deals} and {@code /payment-plan-templates}.
 *
 * <p>Every money figure crosses the wire as a decimal string, never a JSON number (doc 23,
 * doc 22 section 8). A number invites the browser to parse it into a binary float, which is
 * the one representation the whole financial design exists to avoid — and a schedule is
 * precisely where a hundredth of a piastre becomes a customer's complaint.
 *
 * <p>Nothing here is computed. The response carries the figures the backend generated and
 * stored; the client's job is to display them. In particular {@link ScheduleResponse} is
 * built from the persisted rows, so what a screen shows before activation is the same data
 * activation makes live — not a matching calculation, the same rows.
 */
public final class DealDtos {

    private DealDtos() {
    }

    // ------------------------------------------------------------------ deals

    /**
     * @param approvalRequired            whether R-DISC-5's threshold has been crossed
     * @param downPaymentConfirmationApplies whether E5-S7's action exists on this deal at all
     */
    public record DealResponse(UUID id, UUID unitId, UUID primaryCustomerId, UUID agentUserId,
                               UUID branchId, UUID sourceReservationId, LocalDate dealDate,
                               String grossValue, String totalDiscount, String netValue,
                               String currency, String commercialModel, String status,
                               boolean discountApproved, OffsetDateTime discountApprovedAt,
                               boolean approvalRequired,
                               LocalDate downPaymentConfirmedAt,
                               boolean downPaymentConfirmationApplies,
                               OffsetDateTime activatedAt, OffsetDateTime completedAt,
                               OffsetDateTime cancelledAt, String cancelledReason,
                               List<DiscountResponse> discounts, ScheduleResponse schedule) {

        public static DealResponse from(Deal deal, List<DealDiscount> discounts,
                                        PlanWithSchedule plan, boolean approvalRequired,
                                        boolean downPaymentConfirmationApplies) {
            return new DealResponse(deal.id(), deal.unitId(), deal.primaryCustomerId(),
                    deal.agentUserId(), deal.branchId(), deal.sourceReservationId(),
                    deal.dealDate(), deal.grossValue().toPlainString(),
                    deal.totalDiscount().toPlainString(), deal.netValue().toPlainString(),
                    deal.currency(), deal.commercialModel(), deal.status().code(),
                    deal.isDiscountApproved(), deal.discountApprovedAt(), approvalRequired,
                    deal.downPaymentConfirmedAt(), downPaymentConfirmationApplies,
                    deal.activatedAt(), deal.completedAt(), deal.cancelledAt(),
                    deal.cancelledReason(),
                    discounts.stream().map(DiscountResponse::from).toList(),
                    plan == null ? null : ScheduleResponse.from(plan));
        }
    }

    /**
     * One concession.
     *
     * <p>{@code value} is the rate for a percentage and the amount for a fixed sum, and
     * {@code kind} says which — the same two columns the database keeps, for the same
     * reason. A response that showed only the resolved money would lose the fact that what
     * was agreed was five per cent.
     */
    public record DiscountResponse(UUID id, String kind, String value, String amount,
                                   String reason, OffsetDateTime createdAt) {

        public static DiscountResponse from(DealDiscount discount) {
            return new DiscountResponse(discount.id(), discount.kind().code(),
                    statedValueOf(discount.concession()), discount.amount().toPlainString(),
                    discount.reason(), discount.createdAt());
        }

        private static String statedValueOf(Concession concession) {
            return switch (concession) {
                case Concession.OfPercent percent -> percent.rate().toString();
                case Concession.OfAmount fixed -> fixed.amount().toPlainString();
            };
        }
    }

    /**
     * The generated schedule, read back from storage.
     *
     * <p>{@code total} is the sum of the live rows, sent so a client can display the
     * R-PLAN-4 check rather than perform it. {@code outstanding} is deliberately absent:
     * outstanding means expected minus paid, nothing here knows what was paid, and a field
     * that showed the full expected total under that name would be read as a debt figure.
     */
    public record ScheduleResponse(UUID planId, int version, String status,
                                   UUID sourceTemplateId, String netValue,
                                   String downPaymentAmount, String deliveryPaymentAmount,
                                   String financedAmount, int installmentCount,
                                   String frequency, LocalDate firstDueDate, String total,
                                   List<InstallmentResponse> rows) {

        public static ScheduleResponse from(PlanWithSchedule plan) {
            CustomerPaymentPlan p = plan.plan();
            return new ScheduleResponse(p.id(), p.version(), p.status().code(),
                    p.sourceTemplateId(), p.netValue().toPlainString(),
                    p.downPaymentAmount().toPlainString(),
                    p.deliveryPaymentAmount().toPlainString(),
                    p.financedAmount().toPlainString(), p.installmentCount(),
                    p.frequency().name().toLowerCase(Locale.ROOT), p.firstDueDate(),
                    plan.expectedTotal().toPlainString(),
                    plan.installments().stream().map(InstallmentResponse::from).toList());
        }
    }

    /** One expected obligation. No allocated amount and no paid state: those are Epic 6. */
    public record InstallmentResponse(UUID id, int sequenceNo, String kind, LocalDate dueDate,
                                      String expectedAmount, String status) {

        public static InstallmentResponse from(Installment installment) {
            return new InstallmentResponse(installment.id(), installment.sequenceNo(),
                    installment.kind().code(), installment.dueDate(),
                    installment.expectedAmount().toPlainString(),
                    installment.status().code());
        }
    }

    /**
     * @param sourceReservationId the hold this deal grows out of; required when the unit is
     *                            reserved, because "held by the same party" is checked
     *                            against the named hold rather than assumed
     * @param dealDate            defaults to today, and is the down payment's due date
     */
    public record DraftDealRequest(@NotNull UUID unitId,
                                   @NotNull UUID customerId,
                                   UUID sourceReservationId,
                                   LocalDate dealDate) {
    }

    /**
     * Exactly one of {@code percent} and {@code amount}, both decimal strings.
     *
     * <p>Two fields rather than a kind plus one value, so a request cannot say "percent" and
     * carry an amount. The shape makes the contradiction unspellable instead of validating
     * it away afterwards.
     */
    public record AddDiscountRequest(String percent, String amount,
                                     @Size(max = 500) String reason) {
    }

    public record CancelDealRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * Doc 23's {@code { template_id?, overrides? }} for {@code POST /deals/{id}/payment-plan}.
     *
     * <p>Both optional, and the combinations are all meaningful. A template alone applies it
     * as stored. A template with overrides applies it with the named terms replaced
     * (R-INST-8, and TPL-004's "instance diverges from template"). Overrides alone state a
     * negotiated shape that no stored template has, which is why the template id carries no
     * {@code @NotNull} — the service refuses incomplete terms and names the missing fields,
     * which is a better answer than "templateId must not be null".
     */
    public record ApplyTemplateRequest(UUID templateId, PlanOverridesRequest overrides) {
    }

    /**
     * Terms stated on the deal, each one optional.
     *
     * <p>A null field means "leave it to the template". Down payment keeps the two-field
     * shape the rest of the API uses, so a request cannot say "percent" and carry an amount.
     *
     * @param downPaymentPercent at most one of these two, as a decimal string
     * @param downPaymentAmount  the other
     * @param firstInstallmentOffsetDays days after the deal date; null leaves the template's,
     *                                   or, with no template, one frequency interval
     */
    public record PlanOverridesRequest(String downPaymentPercent,
                                       String downPaymentAmount,
                                       String deliveryPaymentPercent,
                                       @Positive Integer installmentCount,
                                       Frequency frequency,
                                       @PositiveOrZero Integer firstInstallmentOffsetDays) {
    }

    /** R-DP-2: a date and a confirming user, and no amount. */
    public record ConfirmDownPaymentRequest(LocalDate receivedOn) {
    }

    // ------------------------------------------------------------------ templates

    public record TemplateResponse(UUID id, UUID projectId, String name, String shorthandLabel,
                                   String downPaymentType, String downPaymentValue,
                                   String deliveryPaymentPercent, int installmentCount,
                                   String frequency, Integer firstInstallmentOffsetDays,
                                   boolean active, OffsetDateTime createdAt) {

        public static TemplateResponse from(PaymentPlanTemplate template, DownPayment stated) {
            return new TemplateResponse(template.id(), template.projectId(), template.name(),
                    template.shorthandLabel(), template.downPaymentType().code(),
                    statedValueOf(stated), template.deliveryPaymentPercent().toString(),
                    template.installmentCount(),
                    template.frequency().name().toLowerCase(Locale.ROOT),
                    template.firstInstallmentOffsetDays(), template.isActive(),
                    template.createdAt());
        }

        private static String statedValueOf(DownPayment downPayment) {
            return switch (downPayment) {
                case DownPayment.OfPercent percent -> percent.percentage().toString();
                case DownPayment.OfAmount fixed -> fixed.amount().toPlainString();
            };
        }
    }

    /**
     * @param downPaymentPercent one of these two, as a decimal string
     * @param downPaymentAmount  the other
     * @param firstInstallmentOffsetDays null means one frequency interval (doc 17 section 12)
     */
    public record TemplateRequest(UUID projectId,
                                  @NotBlank @Size(max = 200) String name,
                                  @Size(max = 200) String shorthandLabel,
                                  String downPaymentPercent,
                                  String downPaymentAmount,
                                  @NotNull String deliveryPaymentPercent,
                                  int installmentCount,
                                  @NotNull Frequency frequency,
                                  Integer firstInstallmentOffsetDays) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements,
                                  int totalPages) {

        public static <E, T> PageResponse<T> of(Page<E> source, Function<E, T> mapper) {
            return new PageResponse<>(source.getContent().stream().map(mapper).toList(),
                    source.getNumber(), source.getSize(), source.getTotalElements(),
                    source.getTotalPages());
        }
    }
}
