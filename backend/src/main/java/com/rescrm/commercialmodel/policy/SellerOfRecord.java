package com.rescrm.commercialmodel.policy;

/**
 * Who sells the unit, as far as the customer and the contract are concerned.
 *
 * <p>Deliberately not the commercial model under another name. A module asking "who is the
 * seller of record" wants an answer it can act on — whose name goes on the contract, who the
 * customer pays — not a model it would then have to branch on. Two models happen to produce
 * two answers today; a third model would not automatically produce a third.
 */
public enum SellerOfRecord {

    /** The tenant sells its own stock and collects the customer's money. */
    TENANT,

    /** An external developer owns the inventory and collects the customer's money. */
    DEVELOPER
}
