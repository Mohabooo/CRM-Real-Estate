package com.rescrm.commercialmodel.policy;

/**
 * Inventory ownership under {@code own_inventory}: the tenant owns the stock and sells it.
 *
 * <p>No developer exists. Doc 25 section 3 is explicit that creating one to represent the
 * tenant would be a self-referential abstraction serving nothing.
 */
final class OwnInventoryOwnership implements InventoryOwnershipPolicy {

    @Override
    public boolean requiresDeveloper() {
        return false;
    }

    @Override
    public SellerOfRecord sellerOfRecord() {
        return SellerOfRecord.TENANT;
    }
}
