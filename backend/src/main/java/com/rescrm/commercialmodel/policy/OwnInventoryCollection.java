package com.rescrm.commercialmodel.policy;

/** Under {@code own_inventory} the tenant collects the customer's money and tracks it. */
final class OwnInventoryCollection implements CollectionPolicy {

    @Override
    public boolean recordsCustomerPayments() {
        return true;
    }

    @Override
    public DepositMeaning depositMeaning() {
        return DepositMeaning.TENANT_CASH;
    }
}
