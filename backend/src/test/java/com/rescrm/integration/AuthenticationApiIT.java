package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.identity.service.TenantProvisioningService.ProvisionedTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /auth endpoints over HTTP: the cookie, the status codes and the filter behind them.
 *
 * <p>This is the first test in the project that reaches a protected endpoint the way a
 * browser does. Until authentication existed, every such test had to inject a principal,
 * which meant the filter's real path had never been exercised end to end.
 */
@AutoConfigureMockMvc
@DisplayName("The authentication endpoints")
class AuthenticationApiIT extends AbstractPostgresIT {

    private static final String PASSWORD = "a-long-enough-password";
    private static final String COOKIE = "crm_session";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantProvisioningService provisioning;

    private ProvisionedTenant tenant;
    private String slug;
    private String ownerEmail;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        slug = "api-" + unique;
        ownerEmail = "owner-" + unique + "@example.com";
        tenant = provisioning.provision("Api " + unique, slug, "own_inventory", "Main",
                "Owner", ownerEmail, PASSWORD);
    }

    @Test
    @DisplayName("refuse a protected path with no cookie, without saying what is behind it")
    void no_cookie_is_401() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("issue an HttpOnly cookie on a successful sign-in, and no token in the body")
    void login_sets_an_http_only_cookie() throws Exception {
        MvcResult result = login(slug, ownerEmail, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(tenant.owner().id().toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly())
                .as("a readable session cookie is one cross-site scripting bug from stolen")
                .isTrue();
        assertThat(result.getResponse().getContentAsString())
                .as("the token must never travel in the body")
                .doesNotContain(cookie.getValue());
    }

    @Test
    @DisplayName("let that cookie reach a protected path")
    void the_cookie_authenticates() throws Exception {
        Cookie session = signIn();

        mockMvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.tenant().id().toString()))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    @DisplayName("scope everything that cookie reaches to its own tenant")
    void the_cookie_carries_the_tenant() throws Exception {
        Cookie session = signIn();

        mockMvc.perform(get("/api/v1/tenants/current").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenant.tenant().id().toString()))
                .andExpect(jsonPath("$.slug").value(slug));
    }

    @Test
    @DisplayName("refuse a made-up cookie")
    void an_invented_cookie_is_401() throws Exception {
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie(COOKIE, "invented")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("answer 401 with the documented envelope on a bad sign-in")
    void wrong_password_is_401() throws Exception {
        login(slug, ownerEmail, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.correlationId").exists());
    }

    @Test
    @DisplayName("reject a sign-in with fields missing, before touching the database")
    void missing_fields_are_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"\",\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("clear the cookie on sign-out, and stop accepting it")
    void logout_clears_the_cookie() throws Exception {
        Cookie session = signIn();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie cleared = result.getResponse().getCookie(COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();

        mockMvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("answer sign-out with 204 even when there was nothing to sign out of")
    void logout_without_a_session_still_succeeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("refuse a state-changing request that another origin initiated")
    void cross_origin_writes_are_refused() throws Exception {
        Cookie session = signIn();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(session)
                        .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("allow a read from any origin, since a read changes nothing")
    void cross_origin_reads_pass() throws Exception {
        Cookie session = signIn();

        mockMvc.perform(get("/api/v1/me")
                        .cookie(session)
                        .header("Origin", "https://attacker.example"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions login(
            String company, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"company":"%s","email":"%s","password":"%s"}"""
                        .formatted(company, email, password)));
    }

    private Cookie signIn() throws Exception {
        Cookie cookie = login(slug, ownerEmail, PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
