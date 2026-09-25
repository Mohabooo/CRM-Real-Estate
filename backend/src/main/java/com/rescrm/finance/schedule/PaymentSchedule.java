package com.rescrm.finance.schedule;

import com.rescrm.platform.money.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A generated schedule: the rows, in order, and the figures they were derived from.
 *
 * <p>This is what a preview shows and what activation persists — the same object, so the two
 * cannot drift. A preview that recomputed anything would be a second implementation of the
 * arithmetic, and the one that is wrong would be whichever a customer did not see.
 */
public record PaymentSchedule(Money netValue,
                              Money downPaymentAmount,
                              Money deliveryPaymentAmount,
                              Money financedAmount,
                              List<Row> rows) {

    public PaymentSchedule {
        Objects.requireNonNull(netValue, "netValue");
        rows = List.copyOf(rows);
    }

    /** One row of the schedule. Sequence numbers run from 1 across all kinds. */
    public record Row(int sequenceNo, InstallmentKind kind, LocalDate dueDate,
                      Money expectedAmount) {
    }

    /** Every row's amount summed — the figure R-PLAN-4 requires to equal {@link #netValue}. */
    public Money total() {
        return Money.sum(netValue.currency(), rows.stream().map(Row::expectedAmount).toList());
    }

    public List<Row> rowsOfKind(InstallmentKind kind) {
        return rows.stream().filter(row -> row.kind() == kind).toList();
    }

    public LocalDate lastDueDate() {
        return rows.get(rows.size() - 1).dueDate();
    }
}
