package com.rescrm.platform.audit;

/**
 * The actions Epic 1 records in the audit trail.
 *
 * <p>Doc 18 marks transitions as audited per state machine, but defines no state machine for
 * tenancy or identity, so this set is derived from the Epic 1 stories in doc 20: provisioning
 * (E1-S1), invitation and acceptance (E1-S2), and deactivation (E1-S3). Later epics add their
 * own values; the enum is the vocabulary, not a business rule.
 */
public enum AuditAction {
    TENANT_PROVISIONED,
    TENANT_STATUS_CHANGED,
    BRANCH_CREATED,
    BRANCH_UPDATED,
    BRANCH_DEACTIVATED,
    USER_CREATED,
    USER_UPDATED,
    USER_DEACTIVATED,
    USER_REACTIVATED,
    INVITATION_ISSUED,
    INVITATION_ACCEPTED,
    SESSION_STARTED,
    SESSION_ENDED,
    SESSION_REFUSED,

    // Epic 2 — CRM core. Doc 18 section 1 marks assignment, conversion, disqualification and
    // reactivation as audited; the rest are here because a record that can change owner or
    // contact details without a trail cannot be reconciled later.
    LEAD_CAPTURED,
    LEAD_ASSIGNED,
    LEAD_BULK_REASSIGNED,
    LEAD_CONTACTED,
    LEAD_QUALIFIED,
    LEAD_DISQUALIFIED,
    LEAD_REACTIVATED,
    LEAD_CONVERTED,
    LEAD_UPDATED,
    CUSTOMER_CREATED,
    CUSTOMER_UPDATED,
    CUSTOMER_NATIONAL_ID_ACCESSED,
    ACTIVITY_LOGGED,

    // Epic 3 — commercial models and inventory. Doc 18 section 2 marks every unit
    // transition as audited, which is also what serves GET /units/{id}/history: the trail
    // is the history, so there is no second table recording the same thing differently.
    DEVELOPER_REGISTERED,
    DEVELOPER_UPDATED,
    DEVELOPER_DEACTIVATED,
    DEVELOPER_REACTIVATED,
    PROJECT_CREATED,
    PROJECT_UPDATED,
    PROJECT_STATUS_CHANGED,
    PHASE_CREATED,
    PHASE_UPDATED,
    UNIT_CREATED,
    UNIT_UPDATED,
    UNIT_BLOCKED,
    UNIT_UNBLOCKED,
    UNIT_RESERVED,
    UNIT_SOLD,
    UNIT_RETURNED_TO_INVENTORY,
    UNITS_IMPORTED,

    // Epic 4 — reservations. Doc 18 section 3 marks every transition as audited, expiry
    // included: an expiry is the one transition nobody requested, so the trail is the only
    // record that it happened at all.
    RESERVATION_PLACED,
    RESERVATION_CONFIRMED,
    RESERVATION_RELEASED,
    RESERVATION_CANCELLED,
    RESERVATION_EXPIRED,
    RESERVATION_EXTENDED,
    RESERVATION_DEPOSIT_RECORDED,

    /**
     * Doc 18 section 3's last reservation transition, reachable only now that deals exist.
     * Epic 4 declared the state and deliberately left it unreachable.
     */
    RESERVATION_CONVERTED,

    // Epic 5 — deals and payment plans. Doc 18 section 4 marks every deal transition as
    // audited and section 4's second row marks discount edits specifically, which is why
    // discounts have three entries of their own: a concession granted, withdrawn or
    // approved is the part of a price somebody will later be asked to justify.
    DEAL_DRAFTED,
    DEAL_DISCOUNT_ADDED,
    DEAL_DISCOUNT_REMOVED,
    DEAL_DISCOUNT_APPROVED,
    DEAL_ACTIVATED,
    DEAL_DOWN_PAYMENT_CONFIRMED,
    DEAL_COMPLETED,
    DEAL_CANCELLED,

    // Doc 18 section 5. The plan's own transitions are the system's doing on the deal's
    // behalf, but they are recorded separately: a schedule regenerated the day before
    // signing is the kind of thing a customer disputes later.
    PAYMENT_PLAN_GENERATED,
    PAYMENT_PLAN_REGENERATED,
    PAYMENT_PLAN_ACTIVATED,
    PAYMENT_PLAN_CLOSED,
    PAYMENT_PLAN_TEMPLATE_CREATED,
    PAYMENT_PLAN_TEMPLATE_UPDATED,
    PAYMENT_PLAN_TEMPLATE_ARCHIVED,
    PAYMENT_PLAN_TEMPLATE_RESTORED
}
