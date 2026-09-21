package com.rescrm.fixtures;

import com.rescrm.finance.allocation.MoneySplitter;
import com.rescrm.finance.schedule.Frequency;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.time.LocalDate;
import java.util.List;

/**
 * The canonical fixture from doc 17 section 5, as a shared constant.
 *
 * <p>Doc 28 section 14 requires this to be used across test layers rather than re-typed, so a
 * rounding regression fails in several places at once instead of hiding in one.
 *
 * <pre>
 *   Unit list price          3,000,000.00
 *   Discount 5%               -150,000.00
 *   Net value                2,850,000.00
 *   Down payment 10%           285,000.00
 *   Delivery payment 5%        142,500.00
 *   Financed amount          2,422,500.00
 *   32 quarterly installments of 75,703.12, the last carrying a 0.16 remainder
 * </pre>
 */
public final class CanonicalFixture {

    public static final CurrencyCode CURRENCY = CurrencyCode.EGP;

    public static final Money GROSS_VALUE = Money.of("3000000.00", CURRENCY);
    public static final Percentage DISCOUNT_RATE = Percentage.of("5");
    public static final Money DISCOUNT_AMOUNT = Money.of("150000.00", CURRENCY);
    public static final Money NET_VALUE = Money.of("2850000.00", CURRENCY);

    public static final Percentage DOWN_PAYMENT_RATE = Percentage.of("10");
    public static final Money DOWN_PAYMENT = Money.of("285000.00", CURRENCY);

    public static final Percentage DELIVERY_RATE = Percentage.of("5");
    public static final Money DELIVERY_PAYMENT = Money.of("142500.00", CURRENCY);

    public static final Money FINANCED_AMOUNT = Money.of("2422500.00", CURRENCY);

    public static final int INSTALLMENT_COUNT = 32;
    public static final Frequency FREQUENCY = Frequency.QUARTERLY;
    public static final Money BASE_INSTALLMENT = Money.of("75703.12", CURRENCY);
    public static final Money REMAINDER = Money.of("0.16", CURRENCY);
    public static final Money FINAL_INSTALLMENT = Money.of("75703.28", CURRENCY);

    /** Sum of installments 1 through 31, i.e. every installment except the last. */
    public static final Money SUM_OF_ALL_BUT_LAST = Money.of("2346796.72", CURRENCY);

    public static final LocalDate DEAL_DATE = LocalDate.of(2026, 1, 31);
    public static final LocalDate FIRST_DUE_DATE = LocalDate.of(2026, 1, 31);

    private CanonicalFixture() {
    }

    /** The generated installment amounts for the fixture. */
    public static List<Money> installments() {
        return MoneySplitter.split(FINANCED_AMOUNT, INSTALLMENT_COUNT);
    }

    public static Money zero() {
        return Money.zero(CURRENCY);
    }

    public static Money egp(String amount) {
        return Money.of(amount, CURRENCY);
    }
}
