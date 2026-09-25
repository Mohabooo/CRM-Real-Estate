package com.rescrm.support;

import com.rescrm.finance.schedule.DownPayment;
import com.rescrm.finance.schedule.Frequency;
import com.rescrm.finance.schedule.PlanTerms;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * FIN-001 — the canonical fixture, in one place.
 *
 * <p>Doc 29 calls it "the single most important test in the system" and asks for it to be a
 * shared constant used by unit, integration and reconciliation tests, so that a rounding
 * regression fails in several places at once rather than in whichever one somebody happened
 * to write last.
 *
 * <pre>
 * unit list price     3,000,000.00
 * discount 5%          -150,000.00
 * net value           2,850,000.00
 * down payment 10%       285,000.00
 * delivery payment 5%    142,500.00
 * financed            2,422,500.00
 * 8 years quarterly = 32 installments
 * base                    75,703.12   (floor — R-INST-1)
 * remainder                    0.16   → installment 32
 * </pre>
 */
public final class CanonicalDeal {

    public static final CurrencyCode CURRENCY = CurrencyCode.EGP;

    public static final Money LIST_PRICE = Money.of("3000000.00", CURRENCY);
    public static final Percentage DISCOUNT_PERCENT = Percentage.of("5");
    public static final Money TOTAL_DISCOUNT = Money.of("150000.00", CURRENCY);
    public static final Money NET_VALUE = Money.of("2850000.00", CURRENCY);

    public static final Percentage DOWN_PAYMENT_PERCENT = Percentage.of("10");
    public static final Money DOWN_PAYMENT = Money.of("285000.00", CURRENCY);
    public static final Percentage DELIVERY_PERCENT = Percentage.of("5");
    public static final Money DELIVERY_PAYMENT = Money.of("142500.00", CURRENCY);
    public static final Money FINANCED = Money.of("2422500.00", CURRENCY);

    public static final int INSTALLMENT_COUNT = 32;
    public static final Frequency FREQUENCY = Frequency.QUARTERLY;
    public static final Money BASE_INSTALLMENT = Money.of("75703.12", CURRENCY);
    public static final Money FINAL_INSTALLMENT = Money.of("75703.28", CURRENCY);
    public static final Money REMAINDER = Money.of("0.16", CURRENCY);

    /** 31 January, so the fixture also exercises month-end clamping (R-INST-6). */
    public static final LocalDate DEAL_DATE = LocalDate.of(2026, 1, 31);
    public static final LocalDate PROJECT_DELIVERY_DATE = LocalDate.of(2034, 6, 30);

    /** One down payment row, thirty-two installments, one delivery row (FIN-001b). */
    public static final int EXPECTED_ROW_COUNT = INSTALLMENT_COUNT + 2;

    private CanonicalDeal() {
    }

    /** The first four installment dates, which exercise R-INST-6 from a 31st. */
    public static final List<LocalDate> FIRST_FOUR_DUE_DATES = List.of(
            LocalDate.of(2026, 4, 30),
            LocalDate.of(2026, 7, 31),
            LocalDate.of(2026, 10, 31),
            LocalDate.of(2027, 1, 31));

    /**
     * The terms, with no configured offset — so the first installment falls one frequency
     * interval after the deal date, which is doc 17 section 12's documented default.
     *
     * <p>It matters that this is the default and not ninety days. Ninety days after 31
     * January is 1 May, and the whole eight-year schedule would then run on the 1st of a
     * month; one quarter after it is 30 April, and the schedule keeps the original
     * day-of-month with month-end clamping, which is what R-INST-6 describes.
     */
    public static PlanTerms terms() {
        return new PlanTerms(NET_VALUE, DownPayment.percent(DOWN_PAYMENT_PERCENT),
                DELIVERY_PERCENT, INSTALLMENT_COUNT, FREQUENCY, DEAL_DATE,
                Optional.empty(), Optional.of(PROJECT_DELIVERY_DATE));
    }
}
