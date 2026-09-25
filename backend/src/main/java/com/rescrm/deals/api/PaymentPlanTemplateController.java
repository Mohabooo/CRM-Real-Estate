package com.rescrm.deals.api;

import com.rescrm.deals.api.DealDtos.TemplateRequest;
import com.rescrm.deals.api.DealDtos.TemplateResponse;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.service.PaymentPlanService;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.finance.schedule.Frequency;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Doc 23's {@code /payment-plan-templates} (E5-S3).
 *
 * <p>Archiving rather than deleting, throughout. A template with live plans behind it
 * cannot be removed (TPL-003) and would not want to be: the plans copied their terms, so
 * the template's remaining job is to explain where those terms came from.
 */
@RestController
@RequestMapping("/api/v1/payment-plan-templates")
public class PaymentPlanTemplateController {

    private final PaymentPlanService plans;

    public PaymentPlanTemplateController(PaymentPlanService plans) {
        this.plans = plans;
    }

    /** Archived templates included: this is the administration list, not the offer list. */
    @GetMapping
    public List<TemplateResponse> list() {
        return plans.listTemplates().stream().map(PaymentPlanTemplateController::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable UUID id) {
        return toResponse(plans.getTemplate(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody TemplateRequest request) {
        return toResponse(plans.createTemplate(request.projectId(), request.name(),
                request.shorthandLabel(), shapeOf(request)));
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody TemplateRequest request) {
        return toResponse(plans.updateTemplate(id, request.name(), request.shorthandLabel(),
                shapeOf(request)));
    }

    /** TPL-002. Removed from the offer list; live plans are untouched because they copied. */
    @PostMapping("/{id}/archive")
    public TemplateResponse archive(@PathVariable UUID id) {
        return toResponse(plans.archiveTemplate(id));
    }

    @PostMapping("/{id}/restore")
    public TemplateResponse restore(@PathVariable UUID id) {
        return toResponse(plans.restoreTemplate(id));
    }

    private static TemplateResponse toResponse(PaymentPlanTemplate template) {
        return TemplateResponse.from(template, template.downPaymentIn(CurrencyCode.EGP));
    }

    /**
     * Turns the request into the shape the domain takes.
     *
     * <p>Exactly one of the two down-payment fields, for the same reason a discount takes
     * exactly one: "10% down" and "500,000 down" are different statements, and a request
     * carrying both is ambiguous about which the tenant meant.
     */
    private static PaymentPlanTemplate.Shape shapeOf(TemplateRequest request) {
        boolean hasPercent = notBlank(request.downPaymentPercent());
        boolean hasAmount = notBlank(request.downPaymentAmount());
        if (hasPercent == hasAmount) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A down payment is either a percentage or a fixed amount; give exactly one");
        }
        try {
            DownPayment downPayment = hasPercent
                    ? DownPayment.percent(Percentage.of(request.downPaymentPercent().trim()))
                    : DownPayment.fixed(
                            Money.of(request.downPaymentAmount().trim(), CurrencyCode.EGP));

            return new PaymentPlanTemplate.Shape(downPayment,
                    Percentage.of(request.deliveryPaymentPercent().trim()),
                    request.installmentCount(), request.frequency(),
                    request.firstInstallmentOffsetDays());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A percentage or amount in this template is not a valid decimal");
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
