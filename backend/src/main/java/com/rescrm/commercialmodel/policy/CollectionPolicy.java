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

    /**
     * Whether a deal carries a developer down-payment confirmation milestone (R-DP-1, E5-S7).
     *
     * <p>It exists precisely where the tenant does not collect: the customer pays the
     * developer, so the one thing the tenant needs to know — and the thing its inbound
     * commission claim depends on — is the developer's word that the down payment arrived.
     * Where the tenant collects, the ledger already says so and a second confirmation would
     * be a fact recorded twice with two chances to disagree.
     *
     * <p>Derived rather than configured, because it is the same fact as
     * {@link #recordsCustomerPayments()} read from the other side. Stated as its own method
     * so callers ask the question they mean instead of negating an unrelated-sounding one.
     */
    default boolean hasDeveloperDownPaymentConfirmation() {
        return !recordsCustomerPayments();
    }

    /*
     * R-DP-1's predicate itself — down_payment_satisfied(deal) — is deliberately NOT here,
     * and its absence is recorded rather than left to be discovered.
     *
     * The rule resolves by commercial model into three branches. Brokered is answerable
     * today: deal.down_payment_confirmed_at is set, and this module already decides whether
     * that milestone exists at all. R-DP-3's zero-down carve-out is answerable today too.
     * The own-inventory branch is not: it asks whether the down-payment installment is
     * paid, which means allocated_amount against cleared payments — a column that does not
     * exist until Epic 6 and deliberately does not exist yet (doc 22's V7 header).
     *
     * Two thirds of a predicate is worse than none. Its only consumer is R-COMM-6's inbound
     * commission trigger in Epic 8, by which time Epic 6 will have supplied the missing
     * branch; writing it now would mean a method that silently answers false for every
     * own-inventory deal, and the first thing built on it would inherit that as a fact.
     */

    /**
     * Whether completion is a person's decision rather than the arithmetic consequence of a
     * fully-paid schedule (doc 18 section 4, rev 2).
     *
     * <p>Under own inventory the tenant holds the ledger, so "completed" is a fact it can
     * compute. Under brokered inventory it never learns when the customer finished paying
     * the developer, so completion means "our part of the sale is done" and somebody says
     * so. Without this distinction a brokered deal could never reach completed at all, and
     * the confirmed inbound-commission trigger (R-COMM-6) would never fire.
     */
    default boolean completionIsManual() {
        return !recordsCustomerPayments();
    }
}
