package com.rescrm.integration;

import com.rescrm.identity.service.AuthenticationService;
import com.rescrm.identity.service.AuthenticationService.SignedIn;
import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.identity.service.TenantProvisioningService.ProvisionedTenant;
import com.rescrm.identity.service.UserService;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing in, staying signed in, and being signed out (doc 28 section 6).
 *
 * <p>Exercised through the services rather than over HTTP, so the assertions are about what
 * authentication decides rather than how it is transported. {@link AuthenticationApiIT}
 * covers the cookie and the status codes.
 */
@DisplayName("Authentication")
class AuthenticationIT extends AbstractPostgresIT {

    private static final String PASSWORD = "a-long-enough-password";

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private AuthenticationService authentication;
    @Autowired private InvitationService invitations;
    @Autowired private UserService users;

    private ProvisionedTenant tenant;
    private String slug;
    private String ownerEmail;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        slug = "auth-" + unique;
        ownerEmail = "owner-" + unique + "@example.com";
        tenant = provisioning.provision("Auth " + unique, slug, "own_inventory", "Main",
                "Owner", ownerEmail, PASSWORD);
    }

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("signing in")
    class SigningIn {

        @Test
        @DisplayName("returns the caller's tenant, role and branch scope")
        void establishes_the_caller() {
            SignedIn signedIn = authentication.signIn(slug, ownerEmail, PASSWORD);

            AuthenticatedPrincipal principal = signedIn.principal();
            assertThat(principal.tenantId()).isEqualTo(tenant.tenant().id());
            assertThat(principal.userId()).isEqualTo(tenant.owner().id());
            assertThat(principal.role()).isEqualTo(Role.OWNER);
            assertThat(signedIn.rawToken()).isNotBlank();
        }

        @Test
        @DisplayName("accepts the address in any case, as the column stores it normalized")
        void email_is_normalized() {
            assertThat(authentication.signIn(slug, ownerEmail.toUpperCase(), PASSWORD))
                    .isNotNull();
        }

        @Test
        @DisplayName("accepts the company key in any case")
        void slug_is_normalized() {
            assertThat(authentication.signIn(slug.toUpperCase(), ownerEmail, PASSWORD))
                    .isNotNull();
        }

        @Test
        @DisplayName("refuses a wrong password")
        void wrong_password() {
            assertRefused(() -> authentication.signIn(slug, ownerEmail, "not-the-password"));
        }

        @Test
        @DisplayName("refuses an unknown address with the same message as a wrong password")
        void unknown_address_is_indistinguishable() {
            String wrongPassword = messageOf(
                    () -> authentication.signIn(slug, ownerEmail, "not-the-password"));
            String unknownUser = messageOf(
                    () -> authentication.signIn(slug, "nobody@example.com", PASSWORD));
            String unknownCompany = messageOf(
                    () -> authentication.signIn("no-such-company", ownerEmail, PASSWORD));

            assertThat(unknownUser)
                    .as("distinguishing these turns the login form into a user-enumeration oracle")
                    .isEqualTo(wrongPassword)
                    .isEqualTo(unknownCompany);
        }

        @Test
        @DisplayName("refuses a password that belongs to another tenant's user")
        void credentials_do_not_cross_tenants() {
            String other = UUID.randomUUID().toString().substring(0, 8);
            provisioning.provision("Other " + other, "other-" + other, "brokered_inventory",
                    "Main", "Owner", "owner-" + other + "@example.com", PASSWORD);

            assertRefused(() -> authentication.signIn("other-" + other, ownerEmail, PASSWORD));
        }

        @Test
        @DisplayName("refuses a deactivated user")
        void deactivated_user() {
            var agent = inviteAgent();
            deactivate(agent.id());

            assertRefused(() -> authentication.signIn(slug, agent.email(), PASSWORD));
        }
    }

    @Nested
    @DisplayName("staying signed in")
    class StayingSignedIn {

        @Test
        @DisplayName("resolves the cookie back to the same caller")
        void resolves_the_token() {
            SignedIn signedIn = authentication.signIn(slug, ownerEmail, PASSWORD);

            Optional<AuthenticatedPrincipal> resolved =
                    authentication.resolve(signedIn.rawToken());

            assertThat(resolved).isPresent();
            assertThat(resolved.get().userId()).isEqualTo(tenant.owner().id());
            assertThat(resolved.get().tenantId()).isEqualTo(tenant.tenant().id());
        }

        @Test
        @DisplayName("resolves nothing for a token that was never issued")
        void refuses_an_invented_token() {
            assertThat(authentication.resolve("plausible-looking-but-invented")).isEmpty();
            assertThat(authentication.resolve("")).isEmpty();
            assertThat(authentication.resolve(null)).isEmpty();
        }

        @Test
        @DisplayName("stops resolving once the caller signs out")
        void signing_out_ends_it() {
            SignedIn signedIn = authentication.signIn(slug, ownerEmail, PASSWORD);

            authentication.signOut(signedIn.rawToken());

            assertThat(authentication.resolve(signedIn.rawToken())).isEmpty();
        }

        @Test
        @DisplayName("tolerates signing out twice, and signing out something that never existed")
        void signing_out_is_forgiving() {
            SignedIn signedIn = authentication.signIn(slug, ownerEmail, PASSWORD);

            authentication.signOut(signedIn.rawToken());
            authentication.signOut(signedIn.rawToken());
            authentication.signOut("never-issued");
        }

        @Test
        @DisplayName("gives each sign-in its own session, so one device signing out leaves the other")
        void sessions_are_independent() {
            SignedIn laptop = authentication.signIn(slug, ownerEmail, PASSWORD);
            SignedIn phone = authentication.signIn(slug, ownerEmail, PASSWORD);
            assertThat(laptop.rawToken()).isNotEqualTo(phone.rawToken());

            authentication.signOut(laptop.rawToken());

            assertThat(authentication.resolve(laptop.rawToken())).isEmpty();
            assertThat(authentication.resolve(phone.rawToken())).isPresent();
        }
    }

    @Nested
    @DisplayName("when a user is deactivated")
    class WhenDeactivated {

        @Test
        @DisplayName("every session they hold stops working at once (E1-S3)")
        void deactivation_ends_live_sessions() {
            var agent = inviteAgent();
            SignedIn laptop = authentication.signIn(slug, agent.email(), PASSWORD);
            SignedIn phone = authentication.signIn(slug, agent.email(), PASSWORD);

            deactivate(agent.id());

            assertThat(authentication.resolve(laptop.rawToken()))
                    .as("a deactivated user still signed in has not been deactivated")
                    .isEmpty();
            assertThat(authentication.resolve(phone.rawToken())).isEmpty();
        }

        @Test
        @DisplayName("other people's sessions are untouched")
        void only_that_users_sessions_end() {
            var agent = inviteAgent();
            SignedIn ownerSession = authentication.signIn(slug, ownerEmail, PASSWORD);
            authentication.signIn(slug, agent.email(), PASSWORD);

            deactivate(agent.id());

            assertThat(authentication.resolve(ownerSession.rawToken())).isPresent();
        }
    }

    // ------------------------------------------------------------------- helpers

    private com.rescrm.identity.domain.User inviteAgent() {
        SignedIn owner = authentication.signIn(slug, ownerEmail, PASSWORD);
        String agentEmail = "agent-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        InvitationService.IssuedInvitation issued = asOwner(owner,
                () -> invitations.invite(agentEmail, Role.SALES_AGENT,
                        tenant.initialBranch().id()));
        return invitations.accept(issued.rawToken(), "Agent", PASSWORD);
    }

    private void deactivate(UUID userId) {
        SignedIn owner = authentication.signIn(slug, ownerEmail, PASSWORD);
        asOwner(owner, () -> users.deactivate(userId, "Left the company"));
    }

    private <T> T asOwner(SignedIn owner, java.util.function.Supplier<T> action) {
        return TenantContext.callAs(tenant.tenant().id(), () -> {
            SecurityContext.set(owner.principal());
            try {
                return action.get();
            } finally {
                SecurityContext.clear();
            }
        });
    }

    private static void assertRefused(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected the sign-in to be refused");
        } catch (ApiException expected) {
            return expected.getMessage();
        }
    }
}
