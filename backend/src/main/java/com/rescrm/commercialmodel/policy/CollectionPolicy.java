package com.rescrm.commercialmodel.policy;

/**
 * The second of doc 25 section 6's eight policy points: does the tenant record actual
 * customer money for this project, and what does a deposit against it mean?
 *
 * <p>Rule R-PAY-0 is the sharp end. A brokered deal's customer pays the developer, so the
 * tenant does not know what was collected; writing Payment rows anyway would produce
 * outstanding and overdue figures that look authoritative and are fiction. Doc 28 decision
 * A13 chooses rejection over a partially-populated ledger for that reason, and this is where
 * the choice is expressed rather than re-argued at every call site.
 *
 * <p>Epic 4 consumes only {@link #depositMeaning()}. Epics 6 and 7 consume the rest — the
 * interface is complete now so that the payments module inherits a decision rather than
 * making a second one.
 */
public interface CollectionPolicy {

    /** Whether Payment rows may exist against deals on this project's units (R-PAY-0). */
    boolean recordsCustomerPayments();

    /** What a deposit recorded on a reservation represents (E4-S4). */
    DepositMeaning depositMeaning();

    /**
     * Whether outstanding, overdue, aging and cash forecast apply (R-SCOPE-2, R-BRK-1).
     *
     * <p>Distinct from {@link #recordsCustomerPayments()} even though the two agree today:
     * the first is about what may be written, this is about what may be computed from it.
     * Doc 21's design constraint 4 requires the API to say "not applicable for this
     * commercial model" rather than return zeros, because a zero is read as a fact.
     */
    default boolean collectionMetricsApply() {
        return recordsCustomerPayments();
    }
}
