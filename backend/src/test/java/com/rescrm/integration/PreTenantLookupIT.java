package com.rescrm.integration;

import com.rescrm.identity.service.AuthenticationService;
import com.rescrm.identity.service.AuthenticationService.SignedIn;
import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.identity.service.TenantProvisioningService.ProvisionedTenant;
import com.rescrm.platform.security.RandomTokens;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.PlatformAuthenticationLookup;
import com.rescrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three lookups that run before a tenant exists, as a role that row-level security
 * applies to.
 *
 * <p>A login carries a company key, a request carries a cookie, an invitation carries a
 * token — and in all three the tenant is what the query is trying to find out. Each one
 * therefore reads through a narrow policy V6 adds, and each one is invisible to the ordinary
 * isolation policy.
 *
 * <p>This class exists because the suite could not once see the difference: it connected as
 * a superuser, and a superuser bypasses row-level security entirely, so a query that returns
 * nothing in production returned everything here. Invitation acceptance was broken that way
 * for four epics and every test stayed green.
 *
 * <p>{@code AbstractPostgresIT} now connects as an unprivileged role, so these lookups are
 * exercised with the policies in force and need no scaffolding of their own.
 * {@link UnprivilegedConnectionIT} guarantees that property for the whole package.
 */
@DisplayName("Lookups that run before a tenant is established")
class PreTenantLookupIT extends AbstractPostgresIT {

    private static final String PASSWORD = "a-long-enough-password";

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private AuthenticationService authentication;
    @Autowired private InvitationService invitations;
    @Autowired private PlatformAuthenticationLookup lookup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private ProvisionedTenant tenant;
    private String slug;
    private String ownerEmail;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        slug = "pre-" + unique;
        ownerEmail = "owner-" + unique + "@example.com";
        tenant = provisioning.provision("Pre " + unique, slug, "own_inventory", "Main",
                "Owner", ownerEmail, PASSWORD);
    }

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("a company key resolves to its tenant")
    void slug_resolves() {
        var found = lookup.activeTenantBySlug(slug);

        assertThat(found)
                .as("an empty result here is a login form that refuses every correct password")
                .contains(tenant.tenant().id());
    }

    @Test
    @DisplayName("an unknown company key resolves to nothing")
    void unknown_slug_resolves_to_nothing() {
        assertThat(lookup.activeTenantBySlug("no-such-company")).isEmpty();
    }

    @Test
    @DisplayName("a cookie resolves to its session, its tenant and its user")
    void session_resolves() {
        SignedIn signedIn = authentication.signIn(slug, ownerEmail, PASSWORD);

        var found = lookup.liveSessionByTokenHash(RandomTokens.hash(signedIn.rawToken()));

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).isEqualTo(tenant.tenant().id());
        assertThat(found.get().userId()).isEqualTo(tenant.owner().id());
    }

    @Test
    @DisplayName("a revoked cookie resolves to nothing, at the database rather than above it")
    void revoked_session_is_invisible() {
        SignedIn signedIn = authentication.signIn(slug, ownerEmail, PASSWORD);
        authentication.signOut(signedIn.rawToken());

        var found = lookup.liveSessionByTokenHash(RandomTokens.hash(signedIn.rawToken()));

        assertThat(found)
                .as("the policy hides revoked sessions, so a bug in the service cannot revive one")
                .isEmpty();
    }

    @Test
    @DisplayName("an invitation token resolves to its invitation and tenant")
    void invitation_resolves() {
        SignedIn owner = authentication.signIn(slug, ownerEmail, PASSWORD);
        String agentEmail = "agent-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        InvitationService.IssuedInvitation issued =
                TenantContext.callAs(tenant.tenant().id(), () -> {
                    SecurityContext.set(owner.principal());
                    try {
                        return invitations.invite(agentEmail, Role.SALES_AGENT,
                                tenant.initialBranch().id());
                    } finally {
                        SecurityContext.clear();
                    }
                });

        var found = lookup.pendingInvitationByTokenHash(RandomTokens.hash(issued.rawToken()));

        assertThat(found)
                .as("an empty result here is an invitation flow that refuses every valid token")
                .isPresent();
        assertThat(found.get().tenantId()).isEqualTo(tenant.tenant().id());
        assertThat(found.get().invitationId()).isEqualTo(issued.invitation().id());
    }

    @Test
    @DisplayName("an accepted invitation token resolves to nothing")
    void spent_invitation_is_invisible() {
        SignedIn owner = authentication.signIn(slug, ownerEmail, PASSWORD);
        String agentEmail = "agent-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        InvitationService.IssuedInvitation issued =
                TenantContext.callAs(tenant.tenant().id(), () -> {
                    SecurityContext.set(owner.principal());
                    try {
                        return invitations.invite(agentEmail, Role.SALES_AGENT,
                                tenant.initialBranch().id());
                    } finally {
                        SecurityContext.clear();
                    }
                });
        invitations.accept(issued.rawToken(), "Agent", PASSWORD);

        var found = lookup.pendingInvitationByTokenHash(RandomTokens.hash(issued.rawToken()));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("the exemption opens nothing else: units and leads stay invisible")
    void the_exemption_is_narrow() {
        // One transaction so the SET and the SELECT share a connection.
        Long visible = transactions.execute(status -> {
            try {
                jdbc.execute("SET app.platform_task = 'authentication'");
                jdbc.execute("SET app.current_tenant_id = ''");
                return jdbc.queryForObject(
                        "SELECT (SELECT count(*) FROM units) + (SELECT count(*) FROM leads)",
                        Long.class);
            } finally {
                jdbc.execute("SET app.platform_task = ''");
            }
        });

        assertThat(visible).isZero();
    }
}
