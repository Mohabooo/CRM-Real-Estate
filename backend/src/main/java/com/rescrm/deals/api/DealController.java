package com.rescrm.deals.api;

import com.rescrm.deals.api.DealDtos.AddDiscountRequest;
import com.rescrm.deals.api.DealDtos.ApplyTemplateRequest;
import com.rescrm.deals.api.DealDtos.CancelDealRequest;
import com.rescrm.deals.api.DealDtos.ConfirmDownPaymentRequest;
import com.rescrm.deals.api.DealDtos.DealResponse;
import com.rescrm.deals.api.DealDtos.DraftDealRequest;
import com.rescrm.deals.api.DealDtos.PageResponse;
import com.rescrm.deals.api.DealDtos.PlanOverridesRequest;
import com.rescrm.deals.api.DealDtos.ScheduleResponse;
import com.rescrm.deals.api.DealDtos.TemplateResponse;
import com.rescrm.deals.domain.Concession;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.service.DealService;
import com.rescrm.deals.service.PaymentPlanService;
import com.rescrm.deals.service.PlanOverrides;
import com.rescrm.deals.service.PlanWithSchedule;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Doc 23's {@code /deals} (Epic 5).
 *
 * <p>Every response carries the deal, its concessions and its schedule together, because
 * they are one thing on a screen and fetching them separately is how a client ends up
 * showing a net value from one request beside a schedule from another.
 *
 * <p>{@code POST /deals/{id}/payment-plan} generates and STORES the schedule, and the deal
 * response then reads it back. So the preview an agent shows a customer is not a matching
 * calculation — it is the rows activation will make live. There is deliberately no
 * calculate-only endpoint: one would be a second implementation of doc 17's arithmetic, and
 * the day it disagreed the customer would be holding whichever was wrong.
 *
 * <p>No endpoint computes outstanding, overdue or paid. Those need actual payments, which
 * arrive in Epic 6; a zero shown under one of those names would be read as a fact.
 */
@RestController
@RequestMapping("/api/v1/deals")
public class DealController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DealService deals;
    private final PaymentPlanService plans;

    public DealController(DealService deals, PaymentPlanService plans) {
        this.deals = deals;
        this.plans = plans;
    }

    /** Already narrowed to the caller's scope; there is no widening parameter. */
    @GetMapping
    public PageResponse<DealResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                deals.listVisibleToCaller(PageRequest.of(Math.max(page, 0), bounded)),
                this::toResponse);
    }

    @GetMapping("/{id}")
    public DealResponse get(@PathVariable UUID id) {
        return toResponse(deals.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DealResponse draft(@Valid @RequestBody DraftDealRequest request) {
        return toResponse(deals.draft(request.unitId(), request.customerId(),
                request.sourceReservationId(), request.dealDate()));
    }

    @PostMapping("/{id}/discounts")
    public DealResponse addDiscount(@PathVariable UUID id,
                                    @Valid @RequestBody AddDiscountRequest request) {
        return toResponse(deals.addDiscount(id, concessionFrom(request), request.reason()));
    }

    @PostMapping("/{id}/discounts/{discountId}/remove")
    public DealResponse removeDiscount(@PathVariable UUID id, @PathVariable UUID discountId) {
        return toResponse(deals.removeDiscount(id, discountId));
    }

    /** R-DISC-5. A POST rather than a PATCH: approving is an act, not a field edit. */
    @PostMapping("/{id}/discounts/approve")
    public DealResponse approveDiscount(@PathVariable UUID id) {
        return toResponse(deals.approveDiscount(id));
    }

    /** The templates this deal may actually be put on: active, and offered for its project. */
    @GetMapping("/{id}/payment-plan-templates")
    public List<TemplateResponse> offeredTemplates(@PathVariable UUID id) {
        return plans.templatesOfferedForDeal(id).stream()
                .map(template -> TemplateResponse.from(template,
                        template.downPaymentIn(CurrencyCode.EGP)))
                .toList();
    }

    /**
     * E5-S4. Generates the schedule and stores it as a draft plan.
     *
     * <p>Run again, it regenerates in place rather than adding a second draft, so an agent
     * comparing two templates ends up with the one they settled on and no orphans.
     */
    @PostMapping("/{id}/payment-plan")
    public DealResponse applyTemplate(@PathVariable UUID id,
                                      @Valid @RequestBody ApplyTemplateRequest request) {
        plans.apply(id, request.templateId(), overridesFrom(request.overrides()));
        return toResponse(deals.get(id));
    }

    /**
     * Turns the request's stated terms into the shape the service takes (R-INST-8).
     *
     * <p>Absent fields stay absent rather than becoming defaults: null means "leave it to
     * the template", and the service is the one that knows whether there is a template to
     * leave it to. Defaulting here would quietly turn a forgotten field into a stated one.
     */
    private static PlanOverrides overridesFrom(PlanOverridesRequest request) {
        if (request == null) {
            return PlanOverrides.none();
        }
        try {
            return new PlanOverrides(
                    statedDownPayment(request),
                    notBlank(request.deliveryPaymentPercent())
                            ? Percentage.of(request.deliveryPaymentPercent().trim())
                            : null,
                    request.installmentCount(),
                    request.frequency(),
                    request.firstInstallmentOffsetDays());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A percentage or amount in these terms is not a valid decimal");
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * At most one of the two down-payment fields, or neither.
     *
     * <p>"Neither" is allowed here and not on a template, because a template must state a
     * down payment and an override need not: leaving both out says the template's stands.
     * Both together is still refused — "10% down" and "500,000 down" are different
     * statements, and a request carrying both is ambiguous about which was negotiated.
     */
    private static DownPayment statedDownPayment(PlanOverridesRequest request) {
        boolean hasPercent = notBlank(request.downPaymentPercent());
        boolean hasAmount = notBlank(request.downPaymentAmount());
        if (hasPercent && hasAmount) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A down payment is either a percentage or a fixed amount; give at most one");
        }
        if (hasPercent) {
            return DownPayment.percent(Percentage.of(request.downPaymentPercent().trim()));
        }
        if (hasAmount) {
            return DownPayment.fixed(Money.of(request.downPaymentAmount().trim(),
                    CurrencyCode.EGP));
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** The schedule alone, for a screen that already has the deal. */
    @GetMapping("/{id}/payment-plan")
    public ScheduleResponse schedule(@PathVariable UUID id) {
        deals.get(id);
        return plans.planFor(id).map(ScheduleResponse::from).orElse(null);
    }

    @PostMapping("/{id}/activate")
    public DealResponse activate(@PathVariable UUID id) {
        return toResponse(deals.activate(id));
    }

    /** E5-S7, brokered only. Records a date and a confirming user, and creates no payment. */
    @PostMapping("/{id}/down-payment-confirmation")
    public DealResponse confirmDownPayment(@PathVariable UUID id,
                                           @RequestBody(required = false)
                                           ConfirmDownPaymentRequest request) {
        return toResponse(deals.confirmDownPaymentReceivedByDeveloper(id,
                request == null ? null : request.receivedOn()));
    }

    /** E5-S8. Reachable under brokered inventory; refused under own, where Epic 6 decides. */
    @PostMapping("/{id}/complete")
    public DealResponse complete(@PathVariable UUID id) {
        return toResponse(deals.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public DealResponse cancel(@PathVariable UUID id,
                               @Valid @RequestBody CancelDealRequest request) {
        return toResponse(deals.cancel(id, request.reason()));
    }

    /**
     * Assembles the one view a deal screen needs.
     *
     * <p>The reads it makes are all against the same deal in the same request, so they
     * cannot disagree with each other the way three separate round trips could.
     */
    private DealResponse toResponse(Deal deal) {
        PlanWithSchedule plan = plans.planFor(deal.id()).orElse(null);
        return DealResponse.from(deal, deals.discountsOn(deal.id()), plan,
                deals.approvalRequiredFor(deal), deals.downPaymentConfirmationApplies(deal));
    }

    /**
     * Turns the request's one populated field into a concession.
     *
     * <p>Exactly one, checked here: a request naming both is ambiguous about what was agreed
     * and there is no safe way to pick. The parse failure is a 400 naming the field rather
     * than {@code Money}'s refusal surfacing as a 500.
     */
    private static Concession concessionFrom(AddDiscountRequest request) {
        boolean hasPercent = request.percent() != null && !request.percent().isBlank();
        boolean hasAmount = request.amount() != null && !request.amount().isBlank();

        if (hasPercent == hasAmount) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A discount is either a percentage or a fixed amount; give exactly one");
        }
        try {
            return hasPercent
                    ? Concession.ofPercent(Percentage.of(request.percent().trim()))
                    : Concession.ofAmount(Money.of(request.amount().trim(), CurrencyCode.EGP));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'" + (hasPercent ? request.percent() : request.amount())
                            + "' is not a valid " + (hasPercent ? "percentage" : "amount"));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
