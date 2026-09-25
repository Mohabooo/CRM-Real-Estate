package com.rescrm.deals.service;

import com.rescrm.deals.domain.CustomerPaymentPlan;
import com.rescrm.deals.domain.Installment;
import com.rescrm.platform.money.Money;

import java.util.List;

/**
 * A plan and the rows it produced, read back from storage.
 *
 * <p>This is what a preview shows and what activation locks in — the same rows, because
 * applying a template writes them and the preview reads them. The alternative, generating a
 * schedule on the fly for the screen and again for the database, is two implementations of
 * the same arithmetic, and the customer would be holding whichever one was wrong.
 *
 * @param plan         the plan, draft or active
 * @param installments every row including voided ones, in sequence order
 */
public record PlanWithSchedule(CustomerPaymentPlan plan, List<Installment> installments) {

    public PlanWithSchedule {
        installments = List.copyOf(installments);
    }

    /**
     * What the live rows come to — the figure R-PLAN-4 requires to equal the net value.
     *
     * <p>Voided rows are excluded (R-SCOPE-1). In Epic 5 nothing voids one, so the sum is
     * over all of them; the exclusion is written now because the day something does void a
     * row is not the day to remember that this total existed.
     */
    public Money expectedTotal() {
        return Money.sum(plan.netValue().currency(), installments.stream()
                .filter(installment -> installment.status().countsTowardsFinancials())
                .map(Installment::expectedAmount)
                .toList());
    }
}
