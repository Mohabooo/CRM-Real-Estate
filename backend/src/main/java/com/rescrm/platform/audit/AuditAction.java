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
    ACTIVITY_LOGGED
}
