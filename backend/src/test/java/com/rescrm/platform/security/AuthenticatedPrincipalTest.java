package com.rescrm.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Authenticated principal")
class AuthenticatedPrincipalTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRANCH_A = UUID.randomUUID();
    private static final UUID BRANCH_B = UUID.randomUUID();

    @Test
    @DisplayName("a tenant-wide role sees every branch")
    void tenant_wide_sees_all() {
        AuthenticatedPrincipal owner =
                new AuthenticatedPrincipal(USER, TENANT, Role.OWNER, null);
        assertThat(owner.canSeeBranch(BRANCH_A)).isTrue();
        assertThat(owner.canSeeBranch(BRANCH_B)).isTrue();
        assertThat(owner.canSeeBranch(null)).isTrue();
    }

    @Test
    @DisplayName("a branch-scoped role sees only its own branch")
    void branch_scoped_sees_own_only() {
        AuthenticatedPrincipal manager =
                new AuthenticatedPrincipal(USER, TENANT, Role.BRANCH_MANAGER, BRANCH_A);
        assertThat(manager.canSeeBranch(BRANCH_A)).isTrue();
        assertThat(manager.canSeeBranch(BRANCH_B)).isFalse();
        assertThat(manager.canSeeBranch(null)).isFalse();
    }

    @Test
    @DisplayName("a branch-scoped principal cannot exist without a branch")
    void branch_scoped_requires_branch() {
        assertThatThrownBy(() ->
                new AuthenticatedPrincipal(USER, TENANT, Role.SALES_AGENT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tenant and user are mandatory")
    void identity_is_mandatory() {
        assertThatThrownBy(() -> new AuthenticatedPrincipal(null, TENANT, Role.OWNER, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedPrincipal(USER, null, Role.OWNER, null))
                .isInstanceOf(NullPointerException.class);
    }
}
