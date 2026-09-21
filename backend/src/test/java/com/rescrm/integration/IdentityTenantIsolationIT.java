package com.rescrm.integration;

import com.rescrm.identity.repository.BranchRepository;
import com.rescrm.identity.repository.InvitationRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.identity.service.BranchService;
import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.identity.service.TenantService;
import com.rescrm.identity.service.UserService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that must fail if tenant isolation is ever weakened.
 *
 * <p>Two tenants are provisioned, then every read and write path is attempted from tenant A
 * against tenant B's rows. This is the application/repository layer of the two-layer design
 * (doc 22 section 4); {@link RlsIsolationIT} proves the database layer independently, and the
 * point of having both is that either one alone could be removed by accident.
 */
@DisplayName("Tenant isolation — identity")
class IdentityTenantIsolationIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private TenantService tenantService;
    @Autowired private BranchService branchService;
    @Autowired private UserService userService;
    @Autowired private InvitationService invitationService;
    @Autowired private BranchRepository branches;
    @Autowired private UserRepository users;
    @Autowired private InvitationRepository invitations;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenantA;
    private TenantProvisioningService.ProvisionedTenant tenantB;
    private UUID branchOfB;
    private UUID userOfB;
    private UUID invitationOfB;

    @BeforeEach
    void provisionTwoTenants() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenantA = provisioning.provision("Tenant A " + unique, "own_inventory", "A Branch",
                "Owner A", "owner-a-" + unique + "@example.com", "a-long-enough-password");
        tenantB = provisioning.provision("Tenant B " + unique, "brokered_inventory", "B Branch",
                "Owner B", "owner-b-" + unique + "@example.com", "a-long-enough-password");

        branchOfB = tenantB.initialBranch().id();
        userOfB = tenantB.owner().id();

        // An outstanding invitation in B, so the cross-tenant attempts have one to aim at.
        asOwnerOf(tenantB);
        invitationOfB = invitationService
                .invite("invitee-b-" + unique + "@example.com", Role.SALES_AGENT, branchOfB)
                .invitation().id();

        // A gets one of its own too. Without it A's list is empty, and "contains only A"
        // would pass against an empty list — the assertion would hold even if scoping were
        // removed entirely, which is the failure mode this guards against.
        asOwnerOf(tenantA);
        invitationService.invite("invitee-a-" + unique + "@example.com", Role.SALES_AGENT,
                tenantA.initialBranch().id());
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private void asOwnerOf(TenantProvisioningService.ProvisionedTenant tenant) {
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .as("cross-tenant access must answer 404, never 403 (doc 28 section 6)")
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Nested
    @DisplayName("Tenant A cannot READ tenant B")
    class CannotRead {

        @Test
        @DisplayName("B's branch is not found")
        void branch_not_found() {
            assertNotFound(() -> branchService.get(branchOfB));
        }

        @Test
        @DisplayName("B's user is not found")
        void user_not_found() {
            assertNotFound(() -> userService.get(userOfB));
        }

        @Test
        @DisplayName("listings contain only A's rows")
        void listings_are_scoped() {
            assertThat(branchService.list())
                    .extracting(b -> b.tenantId())
                    .containsOnly(tenantA.tenant().id());
            assertThat(userService.listVisibleToCaller())
                    .extracting(u -> u.tenantId())
                    .containsOnly(tenantA.tenant().id());
            assertThat(invitationService.list())
                    .extracting(i -> i.tenantId())
                    .containsOnly(tenantA.tenant().id());
        }

        @Test
        @DisplayName("/tenants/current returns A, never B")
        void current_tenant_is_a() {
            assertThat(tenantService.current().id()).isEqualTo(tenantA.tenant().id());
        }

        @Test
        @DisplayName("repository finders scoped to A return nothing for B's ids")
        void repositories_refuse_cross_tenant_ids() {
            UUID a = tenantA.tenant().id();
            assertThat(branches.findByTenantIdAndId(a, branchOfB)).isEmpty();
            assertThat(users.findByTenantIdAndId(a, userOfB)).isEmpty();
            assertThat(invitations.findByTenantIdAndId(a, invitationOfB)).isEmpty();
            assertThat(users.findByTenantIdAndEmail(a, tenantB.owner().email())).isEmpty();
        }

        @Test
        @DisplayName("B's audit trail is invisible to A")
        void audit_trail_is_scoped() {
            assertThat(auditEvents.findTrail(tenantA.tenant().id(), "Tenant",
                    tenantB.tenant().id())).isEmpty();
            assertThat(auditEvents.findForTenant(tenantA.tenant().id()))
                    .extracting(e -> e.tenantId())
                    .containsOnly(tenantA.tenant().id());
        }
    }

    @Nested
    @DisplayName("Tenant A cannot MODIFY tenant B")
    class CannotModify {

        @Test
        @DisplayName("renaming B's branch is not found")
        void cannot_rename_branch() {
            assertNotFound(() -> branchService.rename(branchOfB, "Renamed by A"));
            asOwnerOf(tenantB);
            assertThat(branchService.get(branchOfB).name()).isEqualTo("B Branch");
        }

        @Test
        @DisplayName("deactivating B's user is not found")
        void cannot_deactivate_user() {
            assertNotFound(() -> userService.deactivate(userOfB, "by A"));
            asOwnerOf(tenantB);
            assertThat(userService.get(userOfB).isActive()).isTrue();
        }

        @Test
        @DisplayName("reactivating B's user is not found")
        void cannot_reactivate_user() {
            assertNotFound(() -> userService.reactivate(userOfB));
        }

        @Test
        @DisplayName("changing B's tenant status is not found")
        void cannot_change_tenant_status() {
            assertNotFound(() -> provisioning.changeStatus(tenantB.tenant().id(),
                    com.rescrm.identity.domain.TenantStatus.SUSPENDED, "by A"));
        }
    }

    @Nested
    @DisplayName("Tenant A cannot DEACTIVATE or otherwise remove tenant B's rows")
    class CannotDelete {

        @Test
        @DisplayName("deactivating B's branch is not found and leaves it active")
        void cannot_deactivate_branch() {
            assertNotFound(() -> branchService.deactivate(branchOfB, "by A"));
            asOwnerOf(tenantB);
            assertThat(branchService.get(branchOfB).isActive()).isTrue();
        }

        @Test
        @DisplayName("B's row counts are unchanged after every attempt above")
        void counts_unchanged() {
            long branchesInB = branches.countByTenantId(tenantB.tenant().id());
            long usersInB = users.countByTenantId(tenantB.tenant().id());
            assertThat(branchesInB).isEqualTo(1);
            assertThat(usersInB).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Tenant-aware foreign keys")
    class ForeignKeys {

        @Test
        @DisplayName("A cannot invite into B's branch")
        void cannot_invite_into_another_tenants_branch() {
            assertThatThrownBy(() -> invitationService.invite(
                    "someone-" + UUID.randomUUID() + "@example.com", Role.SALES_AGENT, branchOfB))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Nested
    @DisplayName("Branch scope within a tenant")
    class BranchScope {

        @Test
        @DisplayName("a branch manager sees only their own branch's users")
        void branch_manager_is_narrowed() {
            asOwnerOf(tenantA);
            var secondBranch = branchService.create("A Second Branch");

            TestIdentity.signOut();
            TestIdentity.signIn(UUID.randomUUID(), tenantA.tenant().id(), Role.BRANCH_MANAGER,
                    secondBranch.id());

            // The owner sits in no branch, so a manager of the second branch sees nobody —
            // and critically, cannot widen the listing, because there is no parameter to widen.
            assertThat(userService.listVisibleToCaller())
                    .allSatisfy(user -> assertThat(user.branchId()).isEqualTo(secondBranch.id()));

            assertNotFound(() -> branchService.get(tenantA.initialBranch().id()));
        }

        @Test
        @DisplayName("a branch-scoped role cannot administer identity at all")
        void branch_manager_cannot_administer() {
            TestIdentity.signOut();
            TestIdentity.signIn(UUID.randomUUID(), tenantA.tenant().id(), Role.SALES_AGENT,
                    tenantA.initialBranch().id());

            assertThatThrownBy(() -> branchService.create("Sneaky"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
}
