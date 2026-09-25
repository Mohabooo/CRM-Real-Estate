package com.rescrm.finance.schedule;

import com.rescrm.finance.allocation.MoneySplitter;
import com.rescrm.platform.money.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns plan terms into the schedule a customer will pay (R-PLAN-1 to R-INST-6).
 *
 * <p>A pure function, and the only place a schedule is produced. A preview and an activation
 * call this same method with the same terms, which is what makes the promise that a preview
 * shows exactly what will be persisted true by construction rather than by discipline.
 *
 * <p>It branches on no commercial model, and cannot: {@link PlanTerms} carries none. Doc 27
 * section 2 requires the engine to behave identically for own-inventory and brokered deals,
 * and FIN-001d asserts a byte-identical schedule across the two.
 *
 * <h2>Order of operations</h2>
 *
 * <p>The sequence is load-bearing and easy to get subtly wrong.
 *
 * <ol>
 *   <li>Down payment and delivery payment are both taken from NET, not gross (R-PLAN-1/2).
 *       On the canonical fixture, 10% of 2,850,000 is 285,000 — taking it from the 3,000,000
 *       gross would give 300,000 and every figure after it would be wrong.</li>
 *   <li>Financed is what remains (R-PLAN-3), so the split can never disagree with the total.</li>
 *   <li>The split FLOORS and puts the remainder on the last installment (R-INST-1/2). Half-up
 *       would make the remainder able to go negative, and the last installment smaller than
 *       the others — which reads as an error to anybody holding the payment book.</li>
 * </ol>
 */
public final class PaymentScheduleGenerator {

    private PaymentScheduleGenerator() {
    }

    public static PaymentSchedule generate(PlanTerms terms) {
        Objects.requireNonNull(terms, "terms");

        Money net = terms.netValue();
        Money down = terms.downPayment().amountOf(net);
        Money delivery = terms.deliveryPercent().applyTo(net);

        if (down.isGreaterThan(net)) {
            throw new IllegalArgumentException(
                    "the down payment " + down + " exceeds the net value " + net);
        }
        Money financed = net.minus(down).minus(delivery);
        if (financed.isNegative()) {
            throw new IllegalArgumentException(
                    "the down payment and delivery payment together exceed the net value");
        }

        List<Money> installmentAmounts = MoneySplitter.split(financed, terms.installmentCount());
        List<LocalDate> dueDates = ScheduleDateCalculator.dueDates(
                ScheduleDateCalculator.firstDueDate(terms.dealDate(),
                        terms.firstInstallmentOffsetDays()),
                terms.frequency(), terms.installmentCount());

        List<PaymentSchedule.Row> rows = new ArrayList<>(terms.installmentCount() + 2);
        int sequence = 1;

        // Due at the deal date. A plan with no down payment still carries the row: R-DP-3
        // needs something to point at, and a schedule whose shape changes with its values is
        // one every reader has to special-case.
        rows.add(new PaymentSchedule.Row(sequence++, InstallmentKind.DOWN_PAYMENT,
                terms.dealDate(), down));

        for (int i = 0; i < terms.installmentCount(); i++) {
            rows.add(new PaymentSchedule.Row(sequence++, InstallmentKind.INSTALLMENT,
                    dueDates.get(i), installmentAmounts.get(i)));
        }

        // FIN-025: the delivery installment's date is the project's delivery date, not a
        // cadence step. Where the project has none, it falls on the last installment rather
        // than on an invented date — being visibly last is honest; a guess is not.
        LocalDate deliveryDate = terms.projectDeliveryDate()
                .orElseGet(() -> dueDates.get(dueDates.size() - 1));
        rows.add(new PaymentSchedule.Row(sequence, InstallmentKind.DELIVERY, deliveryDate,
                delivery));

        PaymentSchedule schedule = new PaymentSchedule(net, down, delivery, financed, rows);

        // R-PLAN-4, asserted at generation. Everything above is arithmetic that has been
        // right for a long time; this is the line that guarantees it stays right, and it
        // costs one addition.
        ScheduleInvariant.check(net, down, delivery, installmentAmounts);
        return schedule;
    }
}
