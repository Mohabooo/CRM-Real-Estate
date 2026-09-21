package com.rescrm.integration;

import com.rescrm.platform.observability.CorrelationId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the foundation's HTTP surface: health probes, the platform info endpoint, the
 * correlation header, and the single error envelope.
 */
@AutoConfigureMockMvc
@DisplayName("Health, readiness and error envelope")
class HealthAndErrorEnvelopeIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("health reports UP with the database connected")
    void health_is_up() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("liveness and readiness probes are exposed")
    void probes_are_exposed() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("the platform info endpoint reports that no business domains are implemented")
    void platform_info() throws Exception {
        mockMvc.perform(get("/api/v1/platform/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("crm-backend"))
                .andExpect(jsonPath("$.businessDomainsImplemented").isEmpty());
    }

    @Test
    @DisplayName("a correlation id is generated and echoed when the client sends none")
    void correlation_id_is_generated() throws Exception {
        mockMvc.perform(get("/api/v1/platform/info"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationId.HEADER));
    }

    @Test
    @DisplayName("a client-supplied correlation id is propagated")
    void correlation_id_is_propagated() throws Exception {
        mockMvc.perform(get("/api/v1/platform/info").header(CorrelationId.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER, "abc-123"));
    }

    @Test
    @DisplayName("a hostile correlation id is replaced rather than echoed into the logs")
    void hostile_correlation_id_is_rejected() throws Exception {
        mockMvc.perform(get("/api/v1/platform/info")
                        .header(CorrelationId.HEADER, "bad\nvalue with spaces"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER,
                        org.hamcrest.Matchers.not("bad\nvalue with spaces")));
    }

    @Test
    @DisplayName("an unknown path is refused in the documented envelope, not a whitelabel page")
    void unknown_path_uses_the_error_envelope() throws Exception {
        // From Epic 1 authentication runs before routing, so an unknown path under /api/v1
        // answers 401 rather than 404. That order is deliberate: answering 404 first would
        // tell an unauthenticated caller which paths exist. The envelope is the point of
        // this test, and it still holds.
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
