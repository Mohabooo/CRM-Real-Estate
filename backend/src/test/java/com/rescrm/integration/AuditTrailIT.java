package com.rescrm.integration;

import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.identity.service.UserService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Doc 20, E1-S4: an immutable trail of sensitive actions, with actor, timestamp and
 * before/after, that cannot be edited or deleted through any API.
 */
@DisplayName("Audit trail")
class AuditTrailIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private UserService users;
    @Autowired private InvitationService invitations;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;

    private TenantProvisioningService.ProvisionedTenant tenant;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Audit " + unique, "own_inventory", "HQ", "Owner",
                "owner-" + unique + "@example.com", "a-long-enough-password");
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    @Test
    @DisplayName("records actor, tenant, entity, action and timestamp")
    void records_the_required_fields() {
        var issued = invitations.invite("someone-" + UUID.randomUUID() + "@example.com",
                Role.SALES_AGENT, tenant.initialBranch().id());

        var trail = auditEvents.findTrail(tenant.tenant().id(), "Invitation",
                issued.invitation().id());

        assertThat(trail).hasSize(1);
        var entry = trail.get(0);
        assertThat(entry.action()).isEqualTo("INVITATION_ISSUED");
        assertThat(entry.actorUserId()).isEqualTo(tenant.owner().id());
        assertThat(entry.tenantId()).isEqualTo(tenant.tenant().id());
        assertThat(entry.entityType()).isEqualTo("Invitation");
        assertThat(entry.entityId()).isEqualTo(issued.invitation().id());
        assertThat(entry.createdAt()).isNotNull();
        assertThat(entry.correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("a state change records both before and after")
    void records_before_and_after() {
        var issued = invitations.invite("joiner-" + UUID.randomUUID() + "@example.com",
                Role.SALES_AGENT, tenant.initialBranch().id());
        TestIdentity.signOut();
        var joiner = invitations.accept(issued.rawToken(), "Joiner", "another-long-password");

        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
        users.deactivate(joiner.id(), "left the company");

        var trail = auditEvents.findTrail(tenant.tenant().id(), "User", joiner.id());
        var deactivation = trail.stream()
                .filter(e -> e.action().equals("USER_DEACTIVATED"))
                .findFirst()
                .orElseThrow();

        assertThat(deactivation.before()).contains("true");
        assertThat(deactivation.after()).contains("false");
        assertThat(deactivation.reason()).isEqualTo("left the company");
        assertThat(deactivation.actorUserId()).isEqualTo(tenant.owner().id());
    }

    @Test
    @DisplayName("deactivation does not remove the user, so attribution survives")
    void deactivation_preserves_the_record() {
        var issued = invitations.invite("keeper-" + UUID.randomUUID() + "@example.com",
                Role.SALES_AGENT, tenant.initialBranch().id());
        TestIdentity.signOut();
        var joiner = invitations.accept(issued.rawToken(), "Keeper", "another-long-password");

        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
        users.deactivate(joiner.id(), null);

        var stillThere = users.get(joiner.id());
        assertThat(stillThere.isActive()).isFalse();
        assertThat(stillThere.id()).isEqualTo(joiner.id());
        assertThat(stillThere.email()).isEqualTo(joiner.email());
    }

    @Test
    @DisplayName("an audit row cannot be updated through any path")
    void audit_rows_cannot_be_updated() {
        var trail = auditEvents.findTrail(tenant.tenant().id(), "Tenant", tenant.tenant().id());
        assertThat(trail).isNotEmpty();
        UUID entryId = trail.get(0).id();

        assertThatThrownBy(() ->
                jdbc.update("UPDATE audit_events SET action = 'TAMPERED' WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an audit row cannot be deleted through any path")
    void audit_rows_cannot_be_deleted() {
        var trail = auditEvents.findTrail(tenant.tenant().id(), "Tenant", tenant.tenant().id());
        UUID entryId = trail.get(0).id();

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM audit_events WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the repository exposes no way to delete or update")
    void repository_has_no_write_path_beyond_append() {
        var methods = AuditEventRepository.class.getMethods();
        assertThat(methods)
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("delete", "deleteById", "deleteAll", "saveAndFlush");
    }

    @Test
    @DisplayName("one tenant's trail is invisible to another")
    void trail_is_tenant_scoped() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var other = provisioning.provision("Other " + unique, "own_inventory", "HQ", "O",
                "other-" + unique + "@example.com", "a-long-enough-password");

        TestIdentity.signOut();
        TestIdentity.signIn(other.owner().id(), other.tenant().id(), Role.OWNER, null);

        assertThat(auditEvents.findTrail(other.tenant().id(), "Tenant", tenant.tenant().id()))
                .isEmpty();
        assertThat(auditEvents.findForTenant(other.tenant().id()))
                .extracting(e -> e.tenantId())
                .containsOnly(other.tenant().id());
    }
}
