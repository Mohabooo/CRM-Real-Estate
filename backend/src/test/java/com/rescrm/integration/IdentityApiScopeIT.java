package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.PrincipalResolver;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP surface of tenant and branch scoping.
 *
 * <p>The question this answers is the one that matters for a multi-tenant API: can a caller
 * widen what they see by changing the request? Every attempt below sends a well-formed request
 * with extra or altered parameters, and none of them change the answer, because the server
 * reads tenant and branch from the principal and nowhere else.
 */
@AutoConfigureMockMvc
@ContextConfiguration(classes = IdentityApiScopeIT.TestPrincipal.class)
@DisplayName("API scope cannot be widened by the client")
class IdentityApiScopeIT extends AbstractPostgresIT {

    /** Supplies the principal the filter would otherwise get from authentication. */
    @TestConfiguration
    static class TestPrincipal {

        static final AtomicReference<AuthenticatedPrincipal> CURRENT = new AtomicReference<>();

        @Bean
        PrincipalResolver testPrincipalResolver() {
            return request -> Optional.ofNullable(CURRENT.get());
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantProvisioningService provisioning;

    private TenantProvisioningService.ProvisionedTenant tenantA;
    private TenantProvisioningService.ProvisionedTenant tenantB;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenantA = provisioning.provision("API A " + unique, "own_inventory", "A Branch", "A",
                "api-a-" + unique + "@example.com", "a-long-enough-password");
        tenantB = provisioning.provision("API B " + unique, "own_inventory", "B Branch", "B",
                "api-b-" + unique + "@example.com", "a-long-enough-password");
        TestIdentity.signOut();
        signInAsOwnerOfA();
    }

    @AfterEach
    void clear() {
        TestPrincipal.CURRENT.set(null);
        TestIdentity.signOut();
    }

    private void signInAsOwnerOfA() {
        TestPrincipal.CURRENT.set(new AuthenticatedPrincipal(
                tenantA.owner().id(), tenantA.tenant().id(), Role.OWNER, null));
    }

    @Test
    @DisplayName("with no principal, a protected path answers 401 rather than leaking anything")
    void unauthenticated_is_refused() throws Exception {
        TestPrincipal.CURRENT.set(null);
        mockMvc.perform(get("/api/v1/branches")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tenants/current")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a tenantId query parameter is ignored, not honoured")
    void tenant_id_parameter_is_ignored() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/current")
                        .param("tenantId", tenantB.tenant().id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantA.tenant().id().toString()));
    }

    @Test
    @DisplayName("a tenant header is ignored, not honoured")
    void tenant_header_is_ignored() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/current")
                        .header("X-Tenant-Id", tenantB.tenant().id().toString())
                        .header("X-Tenant", tenantB.tenant().id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantA.tenant().id().toString()));
    }

    @Test
    @DisplayName("fetching another tenant's branch by id answers 404, never 403")
    void cross_tenant_branch_is_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/branches/" + tenantB.initialBranch().id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("fetching another tenant's user by id answers 404")
    void cross_tenant_user_is_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + tenantB.owner().id()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the branch listing contains only the caller's tenant")
    void branch_listing_is_scoped() throws Exception {
        mockMvc.perform(get("/api/v1/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("A Branch"));
    }

    @Test
    @DisplayName("a branch-scoped caller cannot widen the user listing with a parameter")
    void branch_manager_cannot_widen_the_listing() throws Exception {
        TestPrincipal.CURRENT.set(new AuthenticatedPrincipal(UUID.randomUUID(),
                tenantA.tenant().id(), Role.BRANCH_MANAGER, tenantA.initialBranch().id()));

        // branchId, all and scope are not parameters the endpoint has. The point is that
        // sending them changes nothing: the filter is the principal, not the query string.
        mockMvc.perform(get("/api/v1/users")
                        .param("branchId", tenantB.initialBranch().id().toString())
                        .param("all", "true")
                        .param("scope", "tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.branchId != '"
                        + tenantA.initialBranch().id() + "')]").isEmpty());
    }

    @Test
    @DisplayName("a role without identity administration is refused a write")
    void role_without_administration_cannot_write() throws Exception {
        TestPrincipal.CURRENT.set(new AuthenticatedPrincipal(UUID.randomUUID(),
                tenantA.tenant().id(), Role.SALES_AGENT, tenantA.initialBranch().id()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/branches")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorised\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/me reports the caller's own scope and nothing else")
    void me_reports_the_caller() throws Exception {
        signInAsOwnerOfA();
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantA.tenant().id().toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.mayAdministerIdentity").value(true));
    }
}
