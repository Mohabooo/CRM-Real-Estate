package com.rescrm.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal endpoint confirming the API is reachable and reporting what is built.
 *
 * <p>Deliberately not a business endpoint. It exists so the frontend has something real to
 * call in Epic 0, and so a deployment can be identified without guessing. Liveness and
 * readiness come from Actuator at {@code /actuator/health/liveness} and
 * {@code /actuator/health/readiness}; this adds build identity, not health.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformInfoController {

    private final String applicationName;
    private final String version;

    public PlatformInfoController(
            @Value("${spring.application.name:crm-backend}") String applicationName,
            @Value("${app.version:0.1.0-SNAPSHOT}") String version) {
        this.applicationName = applicationName;
        this.version = version;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", applicationName);
        body.put("version", version);
        body.put("epic", "Epic 0 — technical foundation");
        body.put("businessDomainsImplemented", java.util.List.of());
        return body;
    }
}
