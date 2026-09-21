package com.rescrm.integration;

import com.rescrm.crm.domain.Lead;
import com.rescrm.crm.service.CustomerService;
import com.rescrm.crm.service.LeadService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP surface of the CRM endpoints, asked the question that matters for a multi-tenant
 * API: can a caller widen what they see by changing the request?
 *
 * <p>Every attempt below is a well-formed request carrying extra or altered parameters, and
 * none of them change the answer, because the server reads tenant and branch from the
 * principal and nowhere else. The companion to {@link IdentityApiScopeIT}, aimed at leads,
 * customers and activities.
 */
@AutoConfigureMockMvc
@ContextConfiguration(classes = CrmApiScopeIT.TestPrincipal.class)
@DisplayName("CRM API scope cannot be widened by the client")
class CrmApiScopeIT extends AbstractPostgresIT {

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
    @Autowired private LeadService leadService;
    @Autowired private CustomerService customerService;

    private TenantProvisioningService.ProvisionedTenant tenantA;
    private TenantProvisioningService.ProvisionedTenant tenantB;
    private UUID leadOfA;
    private UUID leadOfB;
    private UUID customerOfB;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenantA = provisioning.provision("CRM API A " + unique, "own_inventory", "A Branch",
                "A", "crmapi-a-" + unique + "@example.com", "a-long-enough-password");
        tenantB = provisioning.provision("CRM API B " + unique, "own_inventory", "B Branch",
                "B", "crmapi-b-" + unique + "@example.com", "a-long-enough-password");

        // Seeded through the service layer with an explicit principal, because the endpoints
        // under test must not be the only way rows can get there.
        TestIdentity.signOut();
        TestIdentity.signIn(tenantB.owner().id(), tenantB.tenant().id(), Role.OWNER, null);
        leadOfB = leadService.capture("Lead in B", "+201220000002", null, "walk-in",
                tenantB.initialBranch().id(), null, false).id();
        customerOfB = customerService.create(null, "Customer in B", "+201220000003", null,
                null, null, false).id();

        TestIdentity.signOut();
        TestIdentity.signIn(tenantA.owner().id(), tenantA.tenant().id(), Role.OWNER, null);
        leadOfA = leadService.capture("Lead in A", "+201220000001", null, "walk-in",
                tenantA.initialBranch().id(), null, false).id();
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
    @DisplayName("with no principal, every CRM path answers 401 rather than leaking anything")
    void unauthenticated_is_refused() throws Exception {
        TestPrincipal.CURRENT.set(null);
        mockMvc.perform(get("/api/v1/leads")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/leads/" + leadOfB)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/activities")
                        .param("subjectType", "LEAD")
                        .param("subjectId", leadOfB.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a tenantId parameter or header on a listing is ignored, not honoured")
    void tenant_hints_are_ignored() throws Exception {
        mockMvc.perform(get("/api/v1/leads")
                        .param("tenantId", tenantB.tenant().id().toString())
                        .param("branchId", tenantB.initialBranch().id().toString())
                        .param("all", "true")
                        .header("X-Tenant-Id", tenantB.tenant().id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(leadOfA.toString()));
    }

    @Test
    @DisplayName("fetching another tenant's lead or customer by id answers 404, never 403")
    void cross_tenant_reads_are_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/leads/" + leadOfB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/customers/" + customerOfB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/activities")
                        .param("subjectType", "LEAD")
                        .param("subjectId", leadOfB.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a cross-tenant transition answers 404 and changes nothing")
    void cross_tenant_writes_are_not_found() throws Exception {
        mockMvc.perform(post("/api/v1/leads/" + leadOfB + "/disqualify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"taking this one\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/leads/" + leadOfB + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameEn\":\"Stolen\"}"))
                .andExpect(status().isNotFound());

        TestIdentity.signIn(tenantB.owner().id(), tenantB.tenant().id(), Role.OWNER, null);
        org.assertj.core.api.Assertions
                .assertThat(leadService.get(leadOfB).status().code()).isEqualTo("active");
    }

    @Test
    @DisplayName("an activity cannot be logged against another tenant's subject")
    void cross_tenant_activity_is_not_found() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"LEAD\",\"subjectId\":\"" + leadOfB
                                + "\",\"type\":\"CALL\",\"body\":\"smuggled\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the duplicate check does not reach across tenants")
    void duplicate_check_is_scoped() throws Exception {
        // B's lead really does hold this number.
        mockMvc.perform(get("/api/v1/leads/duplicates").param("phone", "+201220000002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // A's own is still found, so the check is scoped rather than broken.
        mockMvc.perform(get("/api/v1/leads/duplicates").param("phone", "01220000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("a response never carries the tenant id back to the client")
    void responses_do_not_echo_the_tenant() throws Exception {
        mockMvc.perform(get("/api/v1/leads/" + leadOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.name").value("Lead in A"));
    }

    @Test
    @DisplayName("a customer response carries hasNationalId, never the identifier")
    void national_id_is_never_serialised() throws Exception {
        signInAsOwnerOfA();
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameEn\":\"Mona Fahmy\",\"phone\":\"01220000009\","
                                + "\"nationalId\":\"29801011234567\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasNationalId").value(true))
                .andExpect(jsonPath("$.nationalId").doesNotExist())
                .andExpect(jsonPath("$.nationalIdCiphertext").doesNotExist());
    }

    @Test
    @DisplayName("a duplicate capture answers 409 carrying the matches, and 201 once confirmed")
    void duplicate_capture_is_a_question_not_a_refusal() throws Exception {
        mockMvc.perform(post("/api/v1/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Same Number\",\"phone\":\"+20 122 000 0001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.details.duplicates.length()").value(1));

        mockMvc.perform(post("/api/v1/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Same Number\",\"phone\":\"+20 122 000 0001\","
                                + "\"confirmDuplicate\":true}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a sales agent cannot reveal a national identifier through the endpoint")
    void reveal_is_restricted_over_http() throws Exception {
        TestPrincipal.CURRENT.set(new AuthenticatedPrincipal(tenantA.owner().id(),
                tenantA.tenant().id(), Role.SALES_AGENT, tenantA.initialBranch().id()));

        mockMvc.perform(post("/api/v1/customers/" + UUID.randomUUID() + "/national-id/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"curious\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("there is no endpoint for a later epic's sub-resource")
    void later_epics_are_not_stubbed() throws Exception {
        // An endpoint that exists and returns an empty list is worse than one that does not:
        // a client cannot tell "no deals" from "deals were never implemented".
        mockMvc.perform(get("/api/v1/customers/" + UUID.randomUUID() + "/deals"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isNotFound());
    }
}
