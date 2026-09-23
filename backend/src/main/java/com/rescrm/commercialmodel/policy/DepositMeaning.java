package com.rescrm.commercialmodel.policy;

/**
 * What a recorded deposit actually is, which depends on who collected it.
 *
 * <p>The amount is the same number in both models and the distinction is invisible on the
 * screen an agent fills in — which is exactly why it has to be explicit in the type system.
 * Doc 25 section 4: under {@code own_inventory} the tenant collects, so the deposit is money
 * the tenant now holds and must carry into the ledger on conversion. Under
 * {@code brokered_inventory} the developer collects, so the same field records a fact the
 * agent was told, and inventing a Payment row from it would put money in the tenant's
 * accounts that never arrived (rule R-PAY-0, decision A13).
 */
public enum DepositMeaning {

    /** Money the tenant holds. Carries into the payment ledger when the deal is created. */
    TENANT_CASH,

    /** A confirmation the developer received it. Creates no Payment record, ever. */
    DEVELOPER_RECEIPT_CONFIRMATION
}
