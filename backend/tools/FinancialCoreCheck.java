import com.rescrm.finance.allocation.MoneySplitter;
import com.rescrm.finance.schedule.Frequency;
import com.rescrm.finance.schedule.ScheduleDateCalculator;
import com.rescrm.finance.schedule.ScheduleInvariant;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.CurrencyMismatchException;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dependency-free smoke check for the financial core.
 *
 * <p>The authoritative test suite is JUnit + jqwik under {@code src/test/java} and runs via
 * {@code mvn verify}. This harness exists because the financial core is deliberately pure JDK,
 * which means it can be compiled and executed with nothing but a JDK — useful when a Maven
 * repository is unreachable, and useful as a five-second sanity check.
 *
 * <p>Run from {@code backend/}:
 * <pre>
 *   javac -d /tmp/fincheck $(find src/main/java -name '*.java' \
 *       -path '*platform/money/*' -not -path '*jackson*' -not -path '*persistence*') \
 *       $(find src/main/java -path '*finance*' -name '*.java')
 *   javac -cp /tmp/fincheck -d /tmp/fincheck tools/FinancialCoreCheck.java
 *   java -cp /tmp/fincheck FinancialCoreCheck
 * </pre>
 */
public final class FinancialCoreCheck {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("Financial core verification");
        System.out.println("===========================");

        canonicalFixture();
        roundingCases();
        conservationProperty();
        moneySemantics();
        percentageCases();
        scheduleDates();
        invariantDetectsViolations();

        System.out.println();
        System.out.println("---------------------------");
        System.out.printf("%d checks, %d failures%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    // ------------------------------------------------------------------ FIN-001

    private static void canonicalFixture() {
        section("FIN-001  canonical fixture (doc 17 section 5)");

        Money gross = Money.of("3000000.00", CurrencyCode.EGP);
        Money discount = Percentage.of("5").applyTo(gross);
        Money net = gross.minus(discount);
        Money down = Percentage.of("10").applyTo(net);
        Money delivery = Percentage.of("5").applyTo(net);
        Money financed = net.minus(down).minus(delivery);

        List<Money> installments = MoneySplitter.split(financed, 32);
        Money base = MoneySplitter.baseAmount(financed, 32);
        Money remainder = MoneySplitter.remainder(financed, 32);
        Money last = installments.get(31);

        report("gross value", gross, "3000000.00");
        report("discount 5%", discount, "150000.00");
        report("net value", net, "2850000.00");
        report("down payment 10%", down, "285000.00");
        report("delivery payment 5%", delivery, "142500.00");
        report("financed amount", financed, "2422500.00");
        report("base installment", base, "75703.12");
        report("rounding remainder", remainder, "0.16");
        report("installment 1", installments.get(0), "75703.12");
        report("installment 31", installments.get(30), "75703.12");
        report("installment 32 (base+remainder)", last, "75703.28");

        expect("installment count is 32", installments.size() == 32, String.valueOf(installments.size()));

        Money firstThirtyOne = Money.zero(CurrencyCode.EGP);
        for (int i = 0; i < 31; i++) {
            firstThirtyOne = firstThirtyOne.plus(installments.get(i));
        }
        report("sum of installments 1-31", firstThirtyOne, "2346796.72");

        Money scheduleTotal = ScheduleInvariant.scheduleTotal(down, delivery, installments);
        report("schedule total", scheduleTotal, "2850000.00");

        boolean invariantHolds = ScheduleInvariant.holds(net, down, delivery, installments);
        expect("R-PLAN-4 invariant holds exactly", invariantHolds, String.valueOf(invariantHolds));

        expect("only the final installment differs from base",
                installments.subList(0, 31).stream().allMatch(m -> m.equals(base)) && !last.equals(base),
                "verified");
    }

    // ------------------------------------------------------------------ rounding

    private static void roundingCases() {
        section("FIN-010..017  rounding");

        Money financed = Money.of("2422500.00", CurrencyCode.EGP);
        report("FIN-010 base uses floor not half-up", MoneySplitter.baseAmount(financed, 32), "75703.12");

        expect("FIN-011 remainder is never negative (1000 random cases)", remainderNeverNegative(), "verified");

        report("FIN-013 exact division leaves zero remainder",
                MoneySplitter.remainder(Money.of("1200000.00", CurrencyCode.EGP), 12), "0.00");

        List<Money> single = MoneySplitter.split(Money.of("1234.57", CurrencyCode.EGP), 1);
        report("FIN-014 single-part split returns the whole amount", single.get(0), "1234.57");

        report("FIN-015 percentage rounds half-up",
                Percentage.of("5").applyTo(Money.of("1000.05", CurrencyCode.EGP)), "50.00");
        report("FIN-015b half-up rounds .005 upward",
                Percentage.of("50").applyTo(Money.of("0.01", CurrencyCode.EGP)), "0.01");

        report("FIN-017 wire format is a plain decimal string",
                Money.of("2850000.00", CurrencyCode.EGP).toPlainString(), "2850000.00");
    }

    private static boolean remainderNeverNegative() {
        Random random = new Random(20260920L);
        for (int i = 0; i < 1000; i++) {
            long cents = random.nextInt(1_000_000_00);
            int parts = 1 + random.nextInt(400);
            Money total = Money.of(new BigDecimal(cents).movePointLeft(2), CurrencyCode.EGP);
            if (MoneySplitter.remainder(total, parts).isNegative()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ FIN-018

    private static void conservationProperty() {
        section("FIN-018  conservation property (randomised, 20000 cases)");

        Random random = new Random(42L);
        int cases = 20000;
        int violations = 0;
        String firstViolation = null;

        for (int i = 0; i < cases; i++) {
            long cents = (long) random.nextInt(2_000_000_00) + 1;
            int parts = 1 + random.nextInt(400);
            Money total = Money.of(new BigDecimal(cents).movePointLeft(2), CurrencyCode.EGP);

            List<Money> parts0 = MoneySplitter.split(total, parts);
            Money sum = Money.sum(CurrencyCode.EGP, parts0);
            if (!sum.equals(total)) {
                violations++;
                if (firstViolation == null) {
                    firstViolation = total + " / " + parts + " summed to " + sum;
                }
            }
        }
        expect("sum(split(total, n)) == total for all " + cases + " cases",
                violations == 0, violations == 0 ? "no violations" : firstViolation);

        // Full schedule form: net = down + delivery + sum(installments)
        int scheduleViolations = 0;
        for (int i = 0; i < 5000; i++) {
            long netCents = 100_00L + (long) random.nextInt(Integer.MAX_VALUE);
            Money net = Money.of(new BigDecimal(netCents).movePointLeft(2), CurrencyCode.EGP);
            int downPct = random.nextInt(31);
            int deliveryPct = random.nextInt(21);
            Money down = Percentage.of(String.valueOf(downPct)).applyTo(net);
            Money delivery = Percentage.of(String.valueOf(deliveryPct)).applyTo(net);
            Money financed = net.minus(down).minus(delivery);
            if (financed.isNegative() || financed.isZero()) {
                continue;
            }
            int count = 1 + random.nextInt(200);
            List<Money> installments = MoneySplitter.split(financed, count);
            if (!ScheduleInvariant.holds(net, down, delivery, installments)) {
                scheduleViolations++;
            }
        }
        expect("down + delivery + sum(installments) == net for 5000 random plans",
                scheduleViolations == 0, scheduleViolations + " violations");
    }

    // ------------------------------------------------------------------ Money

    private static void moneySemantics() {
        section("Money semantics");

        expect("equality ignores trailing-zero scale differences",
                Money.of("1.50", CurrencyCode.EGP).equals(Money.of(new BigDecimal("1.5"), CurrencyCode.EGP)),
                "equal");

        expect("hashCode agrees with equals",
                Money.of("1.50", CurrencyCode.EGP).hashCode()
                        == Money.of(new BigDecimal("1.5"), CurrencyCode.EGP).hashCode(),
                "equal hashes");

        expect("rejects more precision than the currency has",
                throwsIllegalArgument(() -> Money.of("1.005", CurrencyCode.EGP)), "rejected");

        expect("accepts explicit rounding when asked",
                Money.of(new BigDecimal("1.005"), CurrencyCode.EGP, java.math.RoundingMode.HALF_UP)
                        .toPlainString().equals("1.01"),
                "1.01");

        expect("addition and subtraction round-trip",
                Money.of("100.00", CurrencyCode.EGP).plus(Money.of("0.01", CurrencyCode.EGP))
                        .minus(Money.of("0.01", CurrencyCode.EGP))
                        .equals(Money.of("100.00", CurrencyCode.EGP)),
                "round-trips");

        expect("rejects negative part counts",
                throwsIllegalArgument(() -> MoneySplitter.split(Money.of("10.00", CurrencyCode.EGP), 0)),
                "rejected");

        expect("rejects splitting a negative amount",
                throwsIllegalArgument(() ->
                        MoneySplitter.split(Money.of("-10.00", CurrencyCode.EGP), 2)),
                "rejected");

        boolean mismatchCaught = false;
        try {
            Money.of("1.00", CurrencyCode.EGP).plus(Money.zero(CurrencyCode.EGP));
            mismatchCaught = true; // same currency, should succeed
        } catch (CurrencyMismatchException e) {
            mismatchCaught = false;
        }
        expect("same-currency arithmetic succeeds", mismatchCaught, "ok");

        expect("zero is zero", Money.zero(CurrencyCode.EGP).isZero(), "true");
        expect("comparison works", Money.of("2.00", CurrencyCode.EGP)
                .isGreaterThan(Money.of("1.99", CurrencyCode.EGP)), "true");
    }

    private static void percentageCases() {
        section("Percentage");

        report("5% of 3,000,000.00", Percentage.of("5").applyTo(Money.of("3000000.00", CurrencyCode.EGP)),
                "150000.00");
        report("2.5% of 2,850,000.00", Percentage.of("2.5").applyTo(Money.of("2850000.00", CurrencyCode.EGP)),
                "71250.00");
        report("3% of 2,850,000.00 (commission illustration)",
                Percentage.of("3").applyTo(Money.of("2850000.00", CurrencyCode.EGP)), "85500.00");
        report("3% of 3,000,000.00 (gross vs net difference)",
                Percentage.of("3").applyTo(Money.of("3000000.00", CurrencyCode.EGP)), "90000.00");
        expect("rejects negative percentages",
                throwsIllegalArgument(() -> Percentage.of("-1")), "rejected");
        expect("zero percent yields zero",
                Percentage.zero().applyTo(Money.of("123.45", CurrencyCode.EGP)).isZero(), "true");
    }

    // ------------------------------------------------------------------ dates

    private static void scheduleDates() {
        section("FIN-020..027  schedule dates");

        List<LocalDate> monthly =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 1, 31), Frequency.MONTHLY, 5);
        reportDate("FIN-020 31 Jan +0", monthly.get(0), "2026-01-31");
        reportDate("FIN-020 31 Jan +1 clamps to Feb end", monthly.get(1), "2026-02-28");
        reportDate("FIN-020 31 Jan +2 returns to the 31st", monthly.get(2), "2026-03-31");
        reportDate("FIN-020 31 Jan +3", monthly.get(3), "2026-04-30");
        reportDate("FIN-020 31 Jan +4", monthly.get(4), "2026-05-31");

        List<LocalDate> leap =
                ScheduleDateCalculator.dueDates(LocalDate.of(2024, 1, 31), Frequency.MONTHLY, 3);
        reportDate("FIN-021 leap year Feb", leap.get(1), "2024-02-29");
        reportDate("FIN-021 returns to the 31st", leap.get(2), "2024-03-31");

        List<LocalDate> quarterly =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 3, 15), Frequency.QUARTERLY, 4);
        reportDate("FIN-022 quarterly step 1", quarterly.get(1), "2026-06-15");
        reportDate("FIN-022 quarterly step 3", quarterly.get(3), "2026-12-15");

        reportDate("FIN-023 semi-annual",
                ScheduleDateCalculator.dueDateAt(LocalDate.of(2026, 1, 1), Frequency.SEMI_ANNUAL, 1),
                "2026-07-01");
        reportDate("FIN-023 annual",
                ScheduleDateCalculator.dueDateAt(LocalDate.of(2026, 1, 1), Frequency.ANNUAL, 2),
                "2028-01-01");

        reportDate("FIN-024 first due date offset",
                ScheduleDateCalculator.firstDueDate(LocalDate.of(2026, 9, 20), 30), "2026-10-20");

        List<LocalDate> full =
                ScheduleDateCalculator.dueDates(LocalDate.of(2026, 1, 31), Frequency.QUARTERLY, 32);
        boolean ascending = true;
        for (int i = 1; i < full.size(); i++) {
            if (!full.get(i).isAfter(full.get(i - 1))) {
                ascending = false;
                break;
            }
        }
        expect("FIN-027 dates strictly ascending across a 32-quarter plan", ascending, "ascending");
        reportDate("FIN-027 last due date of the canonical plan", full.get(31), "2033-10-31");

        expect("rejects zero count",
                throwsIllegalArgument(() ->
                        ScheduleDateCalculator.dueDates(LocalDate.of(2026, 1, 1), Frequency.MONTHLY, 0)),
                "rejected");
    }

    // ------------------------------------------------------------------ invariant

    private static void invariantDetectsViolations() {
        section("Invariant checker detects violations");

        Money net = Money.of("1000.00", CurrencyCode.EGP);
        Money down = Money.of("100.00", CurrencyCode.EGP);
        Money delivery = Money.zero(CurrencyCode.EGP);
        List<Money> wrong = new ArrayList<>();
        wrong.add(Money.of("899.99", CurrencyCode.EGP)); // one cent short

        boolean threw = false;
        String detail = "";
        try {
            ScheduleInvariant.check(net, down, delivery, wrong);
        } catch (ScheduleInvariant.ScheduleInvariantViolationException e) {
            threw = true;
            detail = "difference " + e.difference().toPlainString();
        }
        expect("a one-cent shortfall is rejected", threw, detail);

        List<Money> right = new ArrayList<>();
        right.add(Money.of("900.00", CurrencyCode.EGP));
        expect("a correct schedule passes",
                ScheduleInvariant.holds(net, down, delivery, right), "holds");
    }

    // ------------------------------------------------------------------ helpers

    private static void section(String title) {
        System.out.println();
        System.out.println("-- " + title);
    }

    private static void report(String label, Money actual, String expected) {
        checks++;
        boolean ok = actual.toPlainString().equals(expected);
        if (!ok) {
            failures++;
        }
        System.out.printf("   [%s] %-48s %s%s%n", ok ? "PASS" : "FAIL", label, actual.toPlainString(),
                ok ? "" : "   (expected " + expected + ")");
    }

    private static void report(String label, String actual, String expected) {
        checks++;
        boolean ok = actual.equals(expected);
        if (!ok) {
            failures++;
        }
        System.out.printf("   [%s] %-48s %s%n", ok ? "PASS" : "FAIL", label, actual);
    }

    private static void reportDate(String label, LocalDate actual, String expected) {
        checks++;
        boolean ok = actual.toString().equals(expected);
        if (!ok) {
            failures++;
        }
        System.out.printf("   [%s] %-48s %s%s%n", ok ? "PASS" : "FAIL", label, actual,
                ok ? "" : "   (expected " + expected + ")");
    }

    private static void expect(String label, boolean condition, String detail) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.printf("   [%s] %-48s %s%n", condition ? "PASS" : "FAIL", label, detail);
    }

    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
