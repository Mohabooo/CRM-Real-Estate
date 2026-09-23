package com.rescrm.commercialmodel.policy;

/**
 * Inventory ownership under {@code brokered_inventory}: an external developer owns the stock
 * and the tenant sells it on their behalf.
 */
final class BrokeredInventoryOwnership implements InventoryOwnershipPolicy {

    @Override
    public boolean requiresDeveloper() {
        return true;
    }

    @Override
    public SellerOfRecord sellerOfRecord() {
        return SellerOfRecord.DEVELOPER;
    }
}
