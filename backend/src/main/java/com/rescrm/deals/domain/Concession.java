package com.rescrm.deals.domain;

import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.util.Objects;

/**
 * A discount as somebody agreed it: a rate off the price, or a sum off the price.
 *
 * <p>R-DISC-1 allows both, and neither is a special case of the other — "5% off" is what a
 * sales campaign announces and "200,000 off" is what a negotiation produces. Modelled the
 * same way {@code DownPayment} models the same duality on the other side of the plan.
 *
 * <p>Resolving one to money always goes against the deal's GROSS value. R-DISC-2 is explicit
 * that discounts are additive and never compounded: 5% then 10% is 15% of gross, not 14.5%.
 * There is deliberately no method here that takes a running net, so compounding is not
 * something a caller can ask for by mistake.
 */
public sealed interface Concession {

    /** What this concession comes to against a deal's gross value. */
    Money amountAgainst(Money grossValue);

    /** How it is stored and displayed (doc 22, {@code deal_discounts.kind}). */
    DiscountKind kind();

    static Concession ofPercent(Percentage rate) {
        return new OfPercent(rate);
    }

    static Concession ofAmount(Money amount) {
        return new OfAmount(amount);
    }

    record OfPercent(Percentage rate) implements Concession {

        public OfPercent {
            Objects.requireNonNull(rate, "rate");
            if (rate.value().signum() <= 0) {
                throw new IllegalArgumentException("a discount must be greater than zero");
            }
            if (rate.exceedsWhole()) {
                throw new IllegalArgumentException("a percentage discount cannot exceed 100%");
            }
        }

        @Override
        public Money amountAgainst(Money grossValue) {
            return rate.applyTo(grossValue);
        }

        @Override
        public DiscountKind kind() {
            return DiscountKind.PERCENT;
        }
    }

    record OfAmount(Money amount) implements Concession {

        public OfAmount {
            Objects.requireNonNull(amount, "amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a discount must be greater than zero");
            }
        }

        @Override
        public Money amountAgainst(Money grossValue) {
            if (!amount.currency().equals(grossValue.currency())) {
                throw new IllegalArgumentException("a discount in " + amount.currency().isoCode()
                        + " cannot apply to a deal priced in " + grossValue.currency().isoCode());
            }
            return amount;
        }

        @Override
        public DiscountKind kind() {
            return DiscountKind.FIXED;
        }
    }
}
