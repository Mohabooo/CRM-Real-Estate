package com.rescrm.integration;

import com.rescrm.identity.domain.InvitationStatus;
import com.rescrm.identity.repository.InvitationRepository;
import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Doc 20, E1-S2: invite a user with a role and branch; they set a password and land scoped. */
@DisplayName("Invitation lifecycle")
class InvitationLifecycleIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private InvitationService invitations;
    @Autowired private InvitationRepository invitationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private String inviteeEmail;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Invites " + unique, "own_inventory", "HQ", "Owner",
                "owner-" + unique + "@example.com", "a-long-enough-password");
        inviteeEmail = "invitee-" + unique + "@example.com";
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    @Test
    @DisplayName("an invitation is issued pending, with the token returned exactly once")
    void issues_pending_with_token() {
        var issued = invitations.invite(inviteeEmail, Role.SALES_AGENT,
                tenant.initialBranch().id());

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.invitation().statusAt(clock.instant()))
                .isEqualTo(InvitationStatus.PENDING);

        // What is stored is the hash, never the token.
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM invitations WHERE id = ?", String.class,
                issued.invitation().id());
        assertThat(storedHash).isNotEqualTo(issued.rawToken()).hasSize(64);
    }

    @Test
    @DisplayName("accepting creates the user scoped to the invited role and branch")
    void acceptance_creates_a_scoped_user() {
        var issued = invitations.invite(inviteeEmail, Role.SALES_AGENT,
                tenant.initialBranch().id());
        TestIdentity.signOut();

        var user = invitations.accept(issued.rawToken(), "New Joiner", "another-long-password");

        assertThat(user.email()).isEqualTo(inviteeEmail);
        assertThat(user.role()).isEqualTo(Role.SALES_AGENT);
        assertThat(user.branchId()).isEqualTo(tenant.initialBranch().id());
        assertThat(user.tenantId()).isEqualTo(tenant.tenant().id());
        assertThat(user.isActive()).isTrue();
        assertThat(user.passwordHash()).doesNotContain("another-long-password");
    }

    @Test
    @DisplayName("the same token cannot be used twice")
    void token_is_single_use() {
        var issued = invitations.invite(inviteeEmail, Role.SALES_AGENT,
                tenant.initialBranch().id());
        TestIdentity.signOut();
        invitations.accept(issued.rawToken(), "First", "another-long-password");

        assertThatThrownBy(() ->
                invitations.accept(issued.rawToken(), "Second", "another-long-password"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("an expired invitation cannot be accepted")
    void expired_cannot_be_accepted() {
        var issued = invitations.invite(inviteeEmail, Role.SALES_AGENT,
                tenant.initialBranch().id());

        // Both timestamps move, because the table refuses an expiry that precedes creation.
        jdbc.update("UPDATE invitations SET created_at = now() - interval '9 days', "
                + "expires_at = now() - interval '2 days' WHERE id = ?", issued.invitation().id());

        TestIdentity.signOut();
        assertThatThrownBy(() ->
                invitations.accept(issued.rawToken(), "Late", "another-long-password"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("an unknown token is indistinguishable from an expired or used one")
    void unknown_token_answers_identically() {
        TestIdentity.signOut();
        assertThatThrownBy(() ->
                invitations.accept("not-a-real-token", "Nobody", "another-long-password"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("a second outstanding invitation for the same address is refused")
    void one_outstanding_invitation_per_address() {
        invitations.invite(inviteeEmail, Role.SALES_AGENT, tenant.initialBranch().id());
        assertThatThrownBy(() ->
                invitations.invite(inviteeEmail, Role.OPERATIONS, null))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("inviting an address that already has an account is refused")
    void cannot_invite_an_existing_account() {
        assertThatThrownBy(() ->
                invitations.invite(tenant.owner().email(), Role.SALES_AGENT,
                        tenant.initialBranch().id()))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("the database, not the service, is what makes a duplicate account impossible")
    void duplicate_account_is_refused_by_the_constraint() {
        // The structural guard behind concurrent acceptance: whichever transaction commits
        // second violates uq_users_tenant_email and fails, rather than creating a second
        // account for one person. Asserted directly so it cannot be removed unnoticed.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, name, role) "
                        + "VALUES (?, ?, ?, 'x', 'Duplicate', 'SALES_AGENT')",
                UUID.randomUUID(), tenant.tenant().id(), tenant.owner().email()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an invitation is only visible inside its own tenant")
    void invitation_is_tenant_scoped() {
        var issued = invitations.invite(inviteeEmail, Role.SALES_AGENT,
                tenant.initialBranch().id());
        UUID otherTenant = UUID.randomUUID();
        assertThat(invitationRepository.findByTenantIdAndId(otherTenant,
                issued.invitation().id())).isEmpty();
    }
}
