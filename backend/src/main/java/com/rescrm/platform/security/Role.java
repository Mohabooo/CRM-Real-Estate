package com.rescrm.platform.security;

/**
 * The seven fixed MVP roles (doc 16, section 3).
 *
 * <p>Fixed by design. Doc 19 records custom RBAC as P2/Future, so this enum is the whole
 * role model: there is no table of roles and no way to define another one through
 * configuration. A new role is a code change and a migration, which is the point — an
 * installation cannot quietly grant itself capabilities the design never reviewed.
 */
public enum Role {

    /** Owner/CEO. Sees the whole tenant. */
    OWNER(BranchScopeRule.TENANT_WIDE, true),

    /** Platform admin. Provisions tenants (doc 20, E1-S1). */
    PLATFORM_ADMIN(BranchScopeRule.TENANT_WIDE, true),

    /** Operations. Tenant-wide; scope not narrowed by any document, see class note below. */
    OPERATIONS(BranchScopeRule.TENANT_WIDE, false),

    /** Finance. Tenant-wide: collections and commission work spans branches. */
    FINANCE(BranchScopeRule.TENANT_WIDE, false),

    /** Branch manager. Sees their branch (doc 28, section 6). */
    BRANCH_MANAGER(BranchScopeRule.OWN_BRANCH, false),

    /**
     * Team leader. Doc 28 section 6 says "TL → team", but the MVP data model has no team
     * entity, so there is nothing to scope to. Treated as branch-scoped for Epic 1 and
     * recorded as an open question rather than resolved by inventing a team.
     */
    TEAM_LEADER(BranchScopeRule.OWN_BRANCH, false),

    /** Sales agent. Sees their own records (doc 28, section 6). */
    SALES_AGENT(BranchScopeRule.OWN_RECORDS, false);

    private final BranchScopeRule branchScope;
    private final boolean mayAdministerIdentity;

    Role(BranchScopeRule branchScope, boolean mayAdministerIdentity) {
        this.branchScope = branchScope;
        this.mayAdministerIdentity = branchScope == null ? false : mayAdministerIdentity;
    }

    public BranchScopeRule branchScope() {
        return branchScope;
    }

    /**
     * Whether this role may create branches, invite users and deactivate users.
     *
     * <p>Granted to Owner and Platform Admin only. Doc 20 attributes invitation (E1-S2) and
     * deactivation (E1-S3) to the owner and provisioning (E1-S1) to the platform admin; no
     * document grants identity administration to anyone else, so no one else has it. The
     * conservative reading is the safe one: widening a permission later is a decision,
     * narrowing one after users rely on it is a regression.
     */
    public boolean mayAdministerIdentity() {
        return mayAdministerIdentity;
    }

    public boolean isTenantWide() {
        return branchScope == BranchScopeRule.TENANT_WIDE;
    }

    /** How far a role can see within its tenant. Tenant isolation is not part of this. */
    public enum BranchScopeRule {
        /** Every branch in the tenant. */
        TENANT_WIDE,
        /** Only the user's own branch. */
        OWN_BRANCH,
        /** Only records the user owns, within their own branch. */
        OWN_RECORDS
    }
}
