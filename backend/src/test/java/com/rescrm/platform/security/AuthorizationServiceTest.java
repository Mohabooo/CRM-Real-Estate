package com.rescrm.platform.security;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Authorization")
class AuthorizationServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID BRANCH_A = UUID.randomUUID();
    private static final UUID BRANCH_B = UUID.randomUUID();

    private final AuthorizationService authorization = new AuthorizationService();

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    private void signedInAs(Role role, UUID tenantId, UUID branchId) {
        SecurityContext.set(new AuthenticatedPrincipal(UUID.randomUUID(), tenantId, role, branchId));
    }

    @Test
    @DisplayName("a cross-tenant record answers 404, never 403")
    void cross_tenant_is_not_found_not_forbidden() {
        signedInAs(Role.OWNER, TENANT_A, null);

        // Doc 28 section 6: a 403 would confirm the record exists in another tenant, which is
        // exactly what an attacker enumerating identifiers wants to learn.
        assertThatThrownBy(() -> authorization.requireSameTenant(TENANT_B, "User"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("a record in the caller's own tenant passes")
    void same_tenant_passes() {
        signedInAs(Role.SALES_AGENT, TENANT_A, BRANCH_A);
        assertThatCode(() -> authorization.requireSameTenant(TENANT_A, "User"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a role without identity administration is forbidden")
    void role_without_administration_is_forbidden() {
        signedInAs(Role.SALES_AGENT, TENANT_A, BRANCH_A);
        assertThatThrownBy(authorization::requireIdentityAdministration)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("an owner may administer identity")
    void owner_may_administer() {
        signedInAs(Role.OWNER, TENANT_A, null);
        assertThatCode(authorization::requireIdentityAdministration).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("another branch answers 404 for a branch-scoped caller")
    void other_branch_is_not_found() {
        signedInAs(Role.BRANCH_MANAGER, TENANT_A, BRANCH_A);
        assertThatThrownBy(() -> authorization.requireBranchVisible(BRANCH_B, "Branch"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("the listing filter comes from the principal, not from a parameter")
    void branch_filter_comes_from_the_principal() {
        signedInAs(Role.BRANCH_MANAGER, TENANT_A, BRANCH_A);
        assertThat(authorization.branchFilterForCaller()).isEqualTo(BRANCH_A);

        SecurityContext.clear();
        signedInAs(Role.OWNER, TENANT_A, null);
        assertThat(authorization.branchFilterForCaller()).isNull();
    }

    @Test
    @DisplayName("with no caller established, every check refuses")
    void no_caller_refuses() {
        assertThatThrownBy(authorization::requireIdentityAdministration)
                .isInstanceOf(SecurityContext.NotAuthenticatedException.class);
    }
}
