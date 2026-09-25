package com.rescrm.finance.schedule;

import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the generator needs, and nothing it does not.
 *
 * <p>Deliberately free of deals, plans, tenants and templates. The generator is a function
 * from these terms to a schedule; doc 27 section 2 requires it to behave identically in both
 * commercial models, and the surest way to keep that true is to give it nothing it could
 * branch on.
 *
 * @param netValue                  the value the schedule must sum to, exactly (R-PLAN-4)
 * @param downPayment               percent or fixed amount (R-PLAN-1)
 * @param deliveryPercent           percent of net held back to delivery (R-PLAN-2)
 * @param installmentCount          how many financed installments; at least one
 * @param frequency                 the cadence between them (R-INST-5)
 * @param dealDate                  when the deal was struck; the down payment is due then
 * @param firstInstallmentOffsetDays offset from the deal date to installment one (R-INST-4)
 * @param projectDeliveryDate       the delivery installment's date (FIN-025), if known
 */
public record PlanTerms(Money netValue,
                        DownPayment downPayment,
                        Percentage deliveryPercent,
                        int installmentCount,
                        Frequency frequency,
                        LocalDate dealDate,
                        int firstInstallmentOffsetDays,
                        Optional<LocalDate> projectDeliveryDate) {

    public PlanTerms {
        Objects.requireNonNull(netValue, "netValue");
        Objects.requireNonNull(downPayment, "downPayment");
        Objects.requireNonNull(deliveryPercent, "deliveryPercent");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(dealDate, "dealDate");
        Objects.requireNonNull(projectDeliveryDate, "projectDeliveryDate");

        if (!netValue.isPositive()) {
            throw new IllegalArgumentException("net value must be positive (R-DISC-4), was "
                    + netValue);
        }
        if (installmentCount < 1) {
            throw new IllegalArgumentException(
                    "a plan has at least one installment, was " + installmentCount);
        }
        if (firstInstallmentOffsetDays < 0) {
            throw new IllegalArgumentException(
                    "the first installment cannot fall before the deal date");
        }
        if (deliveryPercent.exceedsWhole()) {
            throw new IllegalArgumentException(
                    "the delivery payment cannot exceed the whole net value");
        }
    }
}
