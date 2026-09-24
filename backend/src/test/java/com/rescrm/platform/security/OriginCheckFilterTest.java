package com.rescrm.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server half of the cross-site request forgery defence.
 *
 * <p>{@code SameSite=Lax} on the cookie is the other half, but it is enforced by a browser
 * whose version and settings the server does not control. These cases are the ones the
 * server decides for itself.
 */
@DisplayName("The origin check")
class OriginCheckFilterTest {

    private final OriginCheckFilter filter = new OriginCheckFilter(List.of(), objectMapper());

    /**
     * A mapper equivalent to the one the application runs with.
     *
     * <p>A bare {@code ObjectMapper} cannot write {@code java.time.Instant}, and the error
     * envelope this filter produces carries a timestamp — so a test built on one fails while
     * the filter itself is correct. Spring Boot registers this module from the classpath; a
     * hand-built mapper has to ask for it.
     */
    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // application.yml turns this off too, so the envelope written here carries an
                // ISO-8601 timestamp rather than a float, exactly as it does in production.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    @DisplayName("lets a safe method through whatever its origin, since it changes nothing")
    void safe_methods_pass(String method) throws Exception {
        MockHttpServletResponse response = run(method, "https://attacker.example", 80);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("lets a write through when it came from this very server")
    void same_origin_writes_pass() throws Exception {
        MockHttpServletResponse response = run("POST", "http://localhost", 80);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("lets a write through when there is no Origin at all")
    void a_missing_origin_passes() throws Exception {
        MockHttpServletResponse response = run("POST", null, 80);

        assertThat(response.getStatus())
                .as("forgery is a browser attack, and browsers send the header; curl and "
                        + "server-to-server calls must not be broken by this")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("refuses a write from another host")
    void cross_origin_writes_are_refused() throws Exception {
        MockHttpServletResponse response = run("POST", "https://attacker.example", 80);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    @DisplayName("refuses a write from the same host on a different port")
    void a_different_port_is_a_different_origin() throws Exception {
        MockHttpServletResponse response = run("POST", "http://localhost:4000", 80);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("refuses a write from the same host over a different scheme")
    void a_different_scheme_is_a_different_origin() throws Exception {
        MockHttpServletResponse response = run("POST", "https://localhost", 80);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("refuses a malformed Origin rather than guessing what it meant")
    void a_malformed_origin_is_refused() throws Exception {
        MockHttpServletResponse response = run("POST", "not a url", 80);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("lets the development proxy's origin through when it is configured")
    void the_dev_proxy_origin_passes_when_configured() throws Exception {
        // The exact shape that broke: the browser is on :5173, the dev server forwards /api
        // to :8080 rewriting Host but not Origin, so the request arrives on 8080 carrying an
        // Origin of 5173. Without the configured entry this is a 403 on every sign-in, and
        // the message says only that the origin was not accepted.
        OriginCheckFilter configured = new OriginCheckFilter(
                List.of("http://localhost:5173"), objectMapper());

        MockHttpServletRequest request = request("POST", "http://localhost:5173", 8080);
        MockHttpServletResponse response = new MockHttpServletResponse();
        configured.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("still refuses that same origin when it is NOT configured")
    void the_dev_proxy_origin_is_refused_when_not_configured() throws Exception {
        MockHttpServletResponse response = run("POST", "http://localhost:5173", 8080);

        assertThat(response.getStatus())
                .as("a different port is a different origin; being convenient is not a reason "
                        + "to let one through unconfigured")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("lets a configured front-end origin through")
    void configured_origins_pass() throws Exception {
        OriginCheckFilter configured = new OriginCheckFilter(
                List.of("https://app.example.com"), objectMapper());

        MockHttpServletRequest request = request("POST", "https://app.example.com", 80);
        MockHttpServletResponse response = new MockHttpServletResponse();
        configured.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(String method, String origin, int port)
            throws Exception {
        MockHttpServletRequest request = request(method, origin, port);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest request(String method, String origin, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/anything");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(port);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}
