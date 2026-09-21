package com.rescrm.platform.security;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The server-side authorization checks. Doc 28 section 6: authorization is never expressed in
 * the frontend — the UI hides what a user cannot do, and the API refuses it regardless.
 *
 * <p>Two of the three dimensions in that section live here: role and branch scope. Field-level
 * restriction (national ID, commission figures) belongs to the modules that own those fields
 * and arrives with them.
 */
@Service
public class AuthorizationService {

    /** Refuses unless the caller may create branches, invite users or deactivate them. */
    public void requireIdentityAdministration() {
        AuthenticatedPrincipal caller = SecurityContext.require();
        if (!caller.role().mayAdministerIdentity()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Role " + caller.role() + " may not administer users, branches or invitations");
        }
    }

    /**
     * Refuses unless the record belongs to the caller's tenant.
     *
     * <p>Throws {@code NOT_FOUND}, never {@code FORBIDDEN}. A 403 would confirm that the
     * record exists somewhere, which tells an attacker enumerating identifiers exactly what
     * they wanted to know (doc 28, section 6).
     */
    public void requireSameTenant(UUID recordTenantId, String what) {
        AuthenticatedPrincipal caller = SecurityContext.require();
        if (recordTenantId == null || !recordTenantId.equals(caller.tenantId())) {
            throw ApiException.notFound(what);
        }
    }

    /** Refuses unless the caller's branch scope covers the given branch. */
    public void requireBranchVisible(UUID branchId, String what) {
        AuthenticatedPrincipal caller = SecurityContext.require();
        if (!caller.canSeeBranch(branchId)) {
            throw ApiException.notFound(what);
        }
    }

    /**
     * The branch a listing must be narrowed to, or null for a tenant-wide caller.
     *
     * <p>Returned rather than applied so the caller cannot forget it: a repository method that
     * takes a branch argument makes the omission a compile error instead of a leak.
     */
    public UUID branchFilterForCaller() {
        AuthenticatedPrincipal caller = SecurityContext.require();
        return caller.role().isTenantWide() ? null : caller.branchId();
    }
}
