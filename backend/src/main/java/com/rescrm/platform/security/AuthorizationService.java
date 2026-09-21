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
     * Refuses unless the caller holds one of the listed roles.
     *
     * <p>Generic on purpose: which roles may do what is a domain decision, so the domain
     * states it at the call site and platform only enforces it. Doc 18 names the permitted
     * actors per transition, and those lists are what the services pass here.
     */
    public void requireAnyRole(Role... allowed) {
        AuthenticatedPrincipal caller = SecurityContext.require();
        for (Role role : allowed) {
            if (caller.role() == role) {
                return;
            }
        }
        throw new ApiException(ErrorCode.FORBIDDEN,
                "Role " + caller.role() + " may not perform this action");
    }

    /**
     * Whether the caller's scope covers a record owned by someone, in some branch.
     *
     * <p>The three dimensions of doc 28 section 6's branch scope, in one place: tenant-wide
     * roles see everything, branch-scoped roles see their branch, and an agent sees what they
     * own. Records with no owner yet — a freshly captured lead — are visible to anyone whose
     * branch covers them, because an unassigned lead nobody can see is an unassigned lead
     * nobody assigns.
     */
    public boolean canSeeRecord(UUID ownerUserId, UUID branchId) {
        AuthenticatedPrincipal caller = SecurityContext.require();
        return switch (caller.role().branchScope()) {
            case TENANT_WIDE -> true;
            case OWN_BRANCH -> branchId != null && branchId.equals(caller.branchId());
            case OWN_RECORDS -> caller.userId().equals(ownerUserId)
                    || (ownerUserId == null && branchId != null
                        && branchId.equals(caller.branchId()));
        };
    }

    /** As {@link #canSeeRecord}, but refusing with 404 so existence never leaks. */
    public void requireRecordVisible(UUID ownerUserId, UUID branchId, String what) {
        if (!canSeeRecord(ownerUserId, branchId)) {
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
