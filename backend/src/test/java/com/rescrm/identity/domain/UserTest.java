package com.rescrm.identity.domain;

import com.rescrm.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("User")
class UserTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();

    @Test
    @DisplayName("normalises email so one address cannot become two accounts")
    void normalises_email() {
        User user = User.create(TENANT, "  Ali@Example.COM ", "hash", "Ali", null,
                Role.SALES_AGENT, BRANCH);
        assertThat(user.email()).isEqualTo("ali@example.com");
    }

    @Test
    @DisplayName("rejects an address with no local part or no domain")
    void rejects_malformed_email() {
        assertThatThrownBy(() -> User.create(TENANT, "@example.com", "h", "X", null,
                Role.SALES_AGENT, BRANCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create(TENANT, "nodomain", "h", "X", null,
                Role.SALES_AGENT, BRANCH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a branch-scoped role must name a branch")
    void branch_scoped_role_requires_branch() {
        assertThatThrownBy(() -> User.create(TENANT, "a@b.com", "h", "X", null,
                Role.BRANCH_MANAGER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch-scoped");
    }

    @Test
    @DisplayName("a tenant-wide role may have no branch")
    void tenant_wide_role_needs_no_branch() {
        User owner = User.create(TENANT, "owner@b.com", "h", "Owner", null, Role.OWNER, null);
        assertThat(owner.branchId()).isNull();
        assertThat(owner.isActive()).isTrue();
    }

    @Test
    @DisplayName("deactivation keeps the row so attribution survives")
    void deactivation_keeps_the_row() {
        User user = User.create(TENANT, "a@b.com", "h", "A", null, Role.SALES_AGENT, BRANCH);
        UUID id = user.id();
        user.deactivate();
        assertThat(user.isActive()).isFalse();
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.tenantId()).isEqualTo(TENANT);
        user.reactivate();
        assertThat(user.isActive()).isTrue();
    }

    @Test
    @DisplayName("changing to a branch-scoped role without a branch is refused")
    void role_change_keeps_branch_consistent() {
        User owner = User.create(TENANT, "o@b.com", "h", "O", null, Role.OWNER, null);
        assertThatThrownBy(() -> owner.changeRole(Role.SALES_AGENT, null))
                .isInstanceOf(IllegalArgumentException.class);
        owner.changeRole(Role.SALES_AGENT, BRANCH);
        assertThat(owner.role()).isEqualTo(Role.SALES_AGENT);
        assertThat(owner.branchId()).isEqualTo(BRANCH);
    }
}
