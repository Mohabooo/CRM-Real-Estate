package com.rescrm.commercialmodel.policy;

/**
 * Under {@code brokered_inventory} the developer collects, so the tenant has no ledger.
 *
 * <p>Doc 25 section 4 allows exactly one exception, and it is not this one: a brokered deal
 * carries a single down-payment confirmation milestone because the tenant's commission claim
 * depends on it. That is a date and a confirming user on the deal, not a payment, and it
 * belongs to Epic 5.
 */
final class BrokeredInventoryCollection implements CollectionPolicy {

    @Override
    public boolean recordsCustomerPayments() {
        return false;
    }

    @Override
    public DepositMeaning depositMeaning() {
        return DepositMeaning.DEVELOPER_RECEIPT_CONFIRMATION;
    }
}
