package com.rescrm.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Role model")
class RoleTest {

    @Test
    @DisplayName("has exactly the seven MVP roles and no more")
    void seven_fixed_roles() {
        // Doc 19 records custom RBAC as P2. An eighth role appearing here without a
        // corresponding migration and decision is the failure this test exists to catch.
        assertThat(Role.values()).hasSize(7);
        assertThat(Role.values()).containsExactlyInAnyOrder(
                Role.OWNER, Role.PLATFORM_ADMIN, Role.OPERATIONS, Role.FINANCE,
                Role.BRANCH_MANAGER, Role.TEAM_LEADER, Role.SALES_AGENT);
    }

    @Test
    @DisplayName("only owner and platform admin administer identity")
    void identity_administration_is_narrow() {
        assertThat(Role.OWNER.mayAdministerIdentity()).isTrue();
        assertThat(Role.PLATFORM_ADMIN.mayAdministerIdentity()).isTrue();

        assertThat(Role.OPERATIONS.mayAdministerIdentity()).isFalse();
        assertThat(Role.FINANCE.mayAdministerIdentity()).isFalse();
        assertThat(Role.BRANCH_MANAGER.mayAdministerIdentity()).isFalse();
        assertThat(Role.TEAM_LEADER.mayAdministerIdentity()).isFalse();
        assertThat(Role.SALES_AGENT.mayAdministerIdentity()).isFalse();
    }

    @Test
    @DisplayName("branch scope matches doc 28 section 6")
    void branch_scope() {
        assertThat(Role.OWNER.isTenantWide()).isTrue();
        assertThat(Role.BRANCH_MANAGER.branchScope()).isEqualTo(Role.BranchScopeRule.OWN_BRANCH);
        assertThat(Role.TEAM_LEADER.branchScope()).isEqualTo(Role.BranchScopeRule.OWN_BRANCH);
        assertThat(Role.SALES_AGENT.branchScope()).isEqualTo(Role.BranchScopeRule.OWN_RECORDS);
    }
}
