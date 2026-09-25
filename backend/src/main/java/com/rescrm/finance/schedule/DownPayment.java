package com.rescrm.finance.schedule;

import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.util.Objects;

/**
 * A down payment expressed either way (R-PLAN-1: "from percent or fixed amount").
 *
 * <p>Both forms exist in the market and neither is a special case of the other: "10% down" is
 * what a price list advertises, and "500,000 down" is what a negotiation produces. Resolving
 * them to an amount happens once, here, so nothing downstream has to ask which kind it was.
 */
public sealed interface DownPayment {

    /** The amount this down payment comes to, for a given net value. */
    Money amountOf(Money netValue);

    static DownPayment percent(Percentage percentage) {
        return new OfPercent(percentage);
    }

    static DownPayment fixed(Money amount) {
        return new OfAmount(amount);
    }

    /** No down payment at all — a 0%-down plan, which doc 02 section 11 documents as real. */
    static DownPayment none(Money zeroOfTheRightCurrency) {
        return new OfAmount(zeroOfTheRightCurrency);
    }

    record OfPercent(Percentage percentage) implements DownPayment {

        public OfPercent {
            Objects.requireNonNull(percentage, "percentage");
        }

        @Override
        public Money amountOf(Money netValue) {
            return percentage.applyTo(netValue);
        }
    }

    record OfAmount(Money amount) implements DownPayment {

        public OfAmount {
            Objects.requireNonNull(amount, "amount");
            if (amount.isNegative()) {
                throw new IllegalArgumentException("A down payment cannot be negative: " + amount);
            }
        }

        @Override
        public Money amountOf(Money netValue) {
            return amount;
        }
    }
}
