package com.rescrm.commercialmodel;

/**
 * The commercial model a project operates under (doc 25).
 *
 * <p>This enum is the seed of the containment mechanism described in doc 28, section 3: an
 * architecture test forbids any reference to this type outside the {@code commercialmodel}
 * package, so model-dependent behaviour cannot spread through the codebase as scattered
 * {@code if} statements. Later epics add the eight policy interfaces and their two
 * implementations each; nothing else ever reads this enum directly.
 *
 * <p>It ships in Epic 0 — ahead of the projects domain that will carry it — precisely so the
 * architecture rule is real and enforced from the first commit rather than retrofitted after
 * the first violation.
 *
 * <p>Note the deliberate collapse of three business scenarios into two models (doc 25,
 * section 2): a brokerage selling its own stock and a developer selling its own stock are
 * structurally identical — same ownership, same collection responsibility, same absence of
 * external commission — so both map to {@link #OWN_INVENTORY}.
 */
public enum CommercialModel {

    /**
     * The tenant owns the inventory and collects customer money directly. No external
     * commission exists; internal commission does. Full collections, outstanding, overdue
     * and cash forecasting apply.
     */
    OWN_INVENTORY,

    /**
     * An external developer owns the inventory and collects customer money. The tenant earns
     * external (inbound) commission and distributes internal (outbound) commission. Customer
     * payments are not recorded against these deals in MVP, so collection metrics do not apply.
     */
    BROKERED_INVENTORY;

    /** Whether a {@code Developer} record is required for a project under this model (constraint C10). */
    public boolean requiresDeveloper() {
        return this == BROKERED_INVENTORY;
    }

    /** Whether the tenant records actual customer payments under this model (rule R-PAY-0). */
    public boolean tenantCollectsCustomerPayments() {
        return this == OWN_INVENTORY;
    }

    /** Whether inbound (external) commission entitlements exist under this model (rule R-COMM-5). */
    public boolean hasInboundCommission() {
        return this == BROKERED_INVENTORY;
    }
}
