package com.rescrm.integration;

import com.rescrm.identity.domain.TenantStatus;
import com.rescrm.identity.repository.BranchRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doc 20, E1-S1: provisioning a tenant creates an owner user, an initial branch and a default
 * commercial model setting.
 */
@DisplayName("Tenant provisioning")
class TenantProvisioningIT extends AbstractPostgresIT {

    @Autowired
    private TenantProvisioningService provisioning;

    @Autowired
    private BranchRepository branches;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditEventRepository auditEvents;

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    @Test
    @DisplayName("creates the tenant, its first branch and its owner together")
    void creates_all_three() {
        var provisioned = provisioning.provision("Nile Realty", "brokered_inventory",
                "Cairo HQ", "Mona Fahmy", "Mona@NileRealty.com", "a-long-enough-password");

        assertThat(provisioned.tenant().status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(provisioned.tenant().defaultCommercialModel()).isEqualTo("brokered_inventory");
        assertThat(provisioned.initialBranch().name()).isEqualTo("Cairo HQ");
        assertThat(provisioned.initialBranch().tenantId()).isEqualTo(provisioned.tenant().id());

        assertThat(provisioned.owner().role()).isEqualTo(Role.OWNER);
        assertThat(provisioned.owner().email()).isEqualTo("mona@nilerealty.com");
        assertThat(provisioned.owner().branchId())
                .as("an owner is tenant-wide, so it is attached to no branch")
                .isNull();
        assertThat(provisioned.owner().passwordHash())
                .as("the password is never stored as given")
                .doesNotContain("a-long-enough-password");
    }

    @Test
    @DisplayName("the timestamps the database owns are populated")
    void database_timestamps_are_read_back() {
        var provisioned = provisioning.provision("Delta Homes", "own_inventory", "Main",
                "Owner", "owner@delta.example", "a-long-enough-password");

        assertThat(provisioned.tenant().createdAt()).isNotNull();
        assertThat(provisioned.tenant().updatedAt()).isNotNull();
        assertThat(provisioned.initialBranch().createdAt()).isNotNull();
    }

    @Test
    @DisplayName("records the provisioning in the audit trail")
    void provisioning_is_audited() {
        var provisioned = provisioning.provision("Audited Co", "own_inventory", "HQ",
                "Owner", "owner@audited.example", "a-long-enough-password");

        TestIdentity.signIn(provisioned.owner().id(), provisioned.tenant().id(), Role.OWNER, null);

        var trail = auditEvents.findTrail(provisioned.tenant().id(), "Tenant",
                provisioned.tenant().id());
        assertThat(trail).isNotEmpty();
        assertThat(trail.get(0).action()).isEqualTo("TENANT_PROVISIONED");
        assertThat(trail.get(0).tenantId()).isEqualTo(provisioned.tenant().id());
        assertThat(trail.get(0).createdAt()).isNotNull();
    }

    @Test
    @DisplayName("two tenants are fully independent")
    void tenants_are_independent() {
        var first = provisioning.provision("First", "own_inventory", "F1", "A",
                "a@first.example", "a-long-enough-password");
        var second = provisioning.provision("Second", "brokered_inventory", "S1", "B",
                "b@second.example", "a-long-enough-password");

        assertThat(first.tenant().id()).isNotEqualTo(second.tenant().id());

        TestIdentity.signIn(first.owner().id(), first.tenant().id(), Role.OWNER, null);
        assertThat(branches.findAllByTenantIdOrderByNameAsc(first.tenant().id()))
                .extracting(b -> b.name()).containsExactly("F1");
        assertThat(users.countByTenantId(first.tenant().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same email may own accounts in two different tenants")
    void same_email_across_tenants() {
        UUID ignored = UUID.randomUUID();
        var first = provisioning.provision("T1", "own_inventory", "B1", "Person",
                "shared@example.com", "a-long-enough-password");
        var second = provisioning.provision("T2", "own_inventory", "B2", "Person",
                "shared@example.com", "a-long-enough-password");

        assertThat(first.owner().id()).isNotEqualTo(second.owner().id());
        assertThat(ignored).isNotNull();
    }
}
