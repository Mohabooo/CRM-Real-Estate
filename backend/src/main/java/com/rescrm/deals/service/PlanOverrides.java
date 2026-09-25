package com.rescrm.deals.service;

import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.finance.schedule.Frequency;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Percentage;

import java.util.ArrayList;
import java.util.List;

/**
 * Terms stated on one deal, standing in for or on top of a template's (R-INST-8, TPL-004).
 *
 * <p>Doc 23 gives {@code POST /deals/{id}/payment-plan} the body
 * {@code { template_id?, overrides? }}, and the question mark on the template is the point:
 * a deal's terms are negotiated, and requiring every negotiated shape to exist as a stored
 * template first would mean an administration list full of single-use entries named after
 * customers.
 *
 * <p>A null field means "whatever the template says". With no template every field must be
 * present, because there is nothing to fall back to — {@link #missingFor} says which are not.
 *
 * <p>What this is NOT is a way to write a schedule by hand. Doc 17's R-INST-8 permits an
 * override pre-activation with R-PLAN-4 re-asserted, and doc 29's TPL-004 describes it as
 * "overriding down payment on the instance": terms, not rows. Overridden terms still go
 * through {@code PaymentScheduleGenerator}, so the decomposition holds by construction
 * rather than by re-checking somebody's arithmetic. A row-level editor would be a second
 * way to produce a schedule, and the only one able to produce a wrong one.
 *
 * <p>One limitation, stated rather than hidden: an offset cannot be overridden back to "one
 * frequency interval" on a template that names a day count, because null already means "not
 * overridden". Nobody has needed it; the day somebody does, it wants a real field rather
 * than a second sentinel.
 */
public record PlanOverrides(DownPayment downPayment,
                            Percentage deliveryPercent,
                            Integer installmentCount,
                            Frequency frequency,
                            Integer firstInstallmentOffsetDays) {

    /** Nothing overridden: the template's shape stands exactly as stored. */
    public static PlanOverrides none() {
        return new PlanOverrides(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return overriddenTerms().isEmpty();
    }

    /**
     * Which terms this deal states for itself, in a fixed order.
     *
     * <p>Recorded on the audit entry, because TPL-004's question — did this instance diverge
     * from the template it names — cannot be answered from the stored figures alone. The
     * plan keeps a net value and a row count, not the shape it was asked for.
     */
    public List<String> overriddenTerms() {
        List<String> stated = new ArrayList<>();
        if (downPayment != null) {
            stated.add("downPayment");
        }
        if (deliveryPercent != null) {
            stated.add("deliveryPaymentPercent");
        }
        if (installmentCount != null) {
            stated.add("installmentCount");
        }
        if (frequency != null) {
            stated.add("frequency");
        }
        if (firstInstallmentOffsetDays != null) {
            stated.add("firstInstallmentOffsetDays");
        }
        return List.copyOf(stated);
    }

    /**
     * Which terms are missing when no template backs them, in the order a form asks for them.
     *
     * <p>Empty whenever a template is given: every field it does not state, the template does.
     *
     * <p>The offset is absent from the list deliberately. Doc 17 section 12 gives it a
     * documented default of one frequency interval, so leaving it out states that default
     * rather than omitting an answer.
     */
    public List<String> missingFor(PaymentPlanTemplate template) {
        if (template != null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        if (downPayment == null) {
            missing.add("downPayment");
        }
        if (deliveryPercent == null) {
            missing.add("deliveryPaymentPercent");
        }
        if (installmentCount == null) {
            missing.add("installmentCount");
        }
        if (frequency == null) {
            missing.add("frequency");
        }
        return List.copyOf(missing);
    }

    /**
     * The terms these overrides produce on top of a template, or on their own.
     *
     * @param template the shape to fall back to, or null for fully stated terms
     * @throws IllegalStateException if no template backs terms that are not complete —
     *                               callers check {@link #missingFor} first and refuse
     *                               properly, so reaching here means a caller skipped it
     */
    public PaymentPlanTemplate.Shape resolveAgainst(PaymentPlanTemplate template,
                                                    CurrencyCode currency) {
        List<String> missing = missingFor(template);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "these terms are incomplete and no template backs them: " + missing);
        }
        if (template == null) {
            return new PaymentPlanTemplate.Shape(downPayment, deliveryPercent,
                    installmentCount, frequency, firstInstallmentOffsetDays);
        }

        PaymentPlanTemplate.Shape base = template.shape(currency);
        return new PaymentPlanTemplate.Shape(
                downPayment != null ? downPayment : base.downPayment(),
                deliveryPercent != null ? deliveryPercent : base.deliveryPercent(),
                installmentCount != null ? installmentCount : base.installmentCount(),
                frequency != null ? frequency : base.frequency(),
                firstInstallmentOffsetDays != null
                        ? firstInstallmentOffsetDays
                        : base.firstInstallmentOffsetDays());
    }
}
