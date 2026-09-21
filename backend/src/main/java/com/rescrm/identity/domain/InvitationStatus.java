package com.rescrm.identity.domain;

/**
 * Derived, never stored.
 *
 * <p>Doc 22 gives {@code invitations} an {@code expires_at} and an {@code accepted_at} and no
 * status column, so status is computed from those two facts and the clock. Storing it would
 * need a job to flip rows to expired at the right moment, and a missed run would leave a
 * usable invitation that should have lapsed — the same argument doc 18 makes for not storing
 * installment {@code overdue}.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    EXPIRED
}
