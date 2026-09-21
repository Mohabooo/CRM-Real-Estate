package com.rescrm.finance.schedule;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.Money;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The schedule conservation invariant, rule R-PLAN-4 (doc 17):
 *
 * <pre>down_payment + delivery_payment + sum(installments) == net_value</pre>
 *
 * exactly, to the cent, with no tolerance.
 *
 * <p>This is asserted at generation time by the payment-plan engine (Epic 4) and again by
 * database constraint C6 at plan activation (doc 22, §7). It ships in Epic 0 because it is
 * the contract the generator must satisfy, and because having the checker before the
 * generator is what lets the property-based test in doc 29 (FIN-018) be written first.
 *
 * <p>Deliberately not a test utility: a violated invariant in production is a data-integrity
 * failure that must stop the transaction, not merely fail a build. {@link #check} throws;
 * {@link #holds} is offered for callers that want to branch rather than fail.
 */
public final class ScheduleInvariant {

    private ScheduleInvariant() {
    }

    /**
     * Verifies the invariant, throwing if it does not hold.
     *
     * @throws ScheduleInvariantViolationException with the exact discrepancy, never a vague message
     */
    public static void check(Money netValue, Money downPayment, Money deliveryPayment, List<Money> installments) {
        Objects.requireNonNull(netValue, "netValue must not be null");
        Objects.requireNonNull(downPayment, "downPayment must not be null");
        Objects.requireNonNull(deliveryPayment, "deliveryPayment must not be null");
        Objects.requireNonNull(installments, "installments must not be null");

        Money scheduleTotal = scheduleTotal(downPayment, deliveryPayment, installments);
        if (!scheduleTotal.equals(netValue)) {
            throw new ScheduleInvariantViolationException(netValue, scheduleTotal);
        }
    }

    /** Non-throwing form of {@link #check}. */
    public static boolean holds(Money netValue, Money downPayment, Money deliveryPayment, List<Money> installments) {
        return scheduleTotal(downPayment, deliveryPayment, installments).equals(netValue);
    }

    /** The sum of every component of a schedule. */
    public static Money scheduleTotal(Money downPayment, Money deliveryPayment, List<Money> installments) {
        Money total = downPayment.plus(deliveryPayment);
        for (Money installment : installments) {
            total = total.plus(installment);
        }
        return total;
    }

    /**
     * Thrown when a generated schedule does not sum to the net value.
     *
     * <p>Extends {@link ApiException} so the numbers travel with it into the API error
     * envelope automatically — doc 28, section 7 requires financial errors to report the
     * figures involved, because an error that says only "invalid" is useless to someone
     * reconciling a schedule at month end. {@code platform.errors} is framework-free, so this
     * dependency does not compromise the purity of the finance package.
     */
    public static final class ScheduleInvariantViolationException extends ApiException {

        private static final long serialVersionUID = 1L;

        private final transient Money netValue;
        private final transient Money scheduleTotal;
        private final transient Money difference;

        ScheduleInvariantViolationException(Money netValue, Money scheduleTotal) {
            super(ErrorCode.PLAN_INVARIANT_VIOLATION,
                    "Schedule total does not equal net value",
                    details(netValue, scheduleTotal));
            this.netValue = netValue;
            this.scheduleTotal = scheduleTotal;
            this.difference = netValue.minus(scheduleTotal);
        }

        private static Map<String, Object> details(Money netValue, Money scheduleTotal) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("netValue", netValue.toPlainString());
            details.put("scheduleTotal", scheduleTotal.toPlainString());
            details.put("difference", netValue.minus(scheduleTotal).toPlainString());
            return details;
        }

        public Money netValue() {
            return netValue;
        }

        public Money scheduleTotal() {
            return scheduleTotal;
        }

        public Money difference() {
            return difference;
        }
    }
}
