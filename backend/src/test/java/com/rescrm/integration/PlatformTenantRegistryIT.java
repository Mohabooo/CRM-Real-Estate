package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.tenancy.PlatformTenantRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant registry, exercised the way production runs it: as a role that row-level
 * security actually applies to.
 *
 * <p>This exists because of a bug it would have caught. {@link PlatformTenantRegistry} set
 * its policy flag with {@code SET LOCAL}, which outside a transaction block emits a warning
 * and has no effect whatsoever. The sweep that calls it is not transactional, so in
 * production the flag was empty by the time the query ran, the narrow read policy matched
 * nothing, and the registry returned zero tenants — a reservation expiry sweep that silently
 * never expired anything.
 *
 * <p>Every existing test missed it for one reason: the test connection is a superuser, and a
 * superuser bypasses row-level security entirely, so the query returned every tenant no
 * matter what the flag said. That is precisely the failure mode V5's own comment warns
 * about, reproduced in the code the comment is attached to.
 *
 * <p>So the assertions below run under {@code SET ROLE}, on the transaction-bound connection
 * the registry will use, with the policies genuinely in force.
 */
@DisplayName("Platform tenant registry")
class PlatformTenantRegistryIT extends AbstractPostgresIT {

    private static final String PROBE_ROLE = "crm_registry_probe";

    @Autowired
    private PlatformTenantRegistry registry;

    @Autowired
    private TenantProvisioningService provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("finds active tenants as a non-superuser, where row-level security applies")
    void finds_tenants_under_row_level_security() {
        UUID tenantId = provisionOne();

        List<UUID> ids = asProbeRole(registry::activeTenantIds);

        assertThat(ids)
                .as("the registry must see tenants as an unprivileged role; an empty list "
                        + "here is a sweep that silently never runs in production")
                .contains(tenantId);
    }

    @Test
    @DisplayName("the probe role really is not a superuser, or the test above proves nothing")
    void probe_role_is_not_a_superuser() {
        Boolean superuser = asProbeRole(() -> jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class));

        assertThat(superuser).isFalse();
    }

    @Test
    @DisplayName("grants nothing beyond the tenant registry: units stay invisible")
    void the_exemption_opens_only_the_registry() {
        provisionOne();

        Long units = asProbeRole(() -> {
            jdbc.execute("SET app.platform_task = 'reservation-expiry'");
            jdbc.execute("SET app.current_tenant_id = ''");
            return jdbc.queryForObject("SELECT count(*) FROM units", Long.class);
        });

        assertThat(units)
                .as("the platform-task flag must not make business tables readable")
                .isZero();
    }

    private UUID provisionOne() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return provisioning.provision("Registry " + unique, "own_inventory", "Main", "Owner",
                "registry-" + unique + "@example.com", "a-long-enough-password")
                .tenant().id();
    }

    /**
     * Runs the body inside a transaction, as the unprivileged probe role.
     *
     * <p>The transaction matters as much as the role: it binds one connection for the whole
     * body, so the {@code SET ROLE} below and the registry's own query land on the same
     * session. Without it the registry would take a fresh connection from the pool and run
     * as the superuser again, which is exactly the blind spot this class exists to close.
     */
    private <T> T asProbeRole(java.util.function.Supplier<T> body) {
        return transactions.execute(status -> {
            try {
                jdbc.execute("""
                        DO $$ BEGIN
                          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                            CREATE ROLE %s NOSUPERUSER NOINHERIT;
                          END IF;
                        END $$;""".formatted(PROBE_ROLE, PROBE_ROLE));
                jdbc.execute("GRANT USAGE ON SCHEMA public TO " + PROBE_ROLE);
                jdbc.execute("GRANT SELECT ON tenants, units, reservations TO " + PROBE_ROLE);
                jdbc.execute("SET ROLE " + PROBE_ROLE);
                return body.get();
            } finally {
                jdbc.execute("RESET ROLE");
                jdbc.execute("SET app.platform_task = ''");
            }
        });
    }
}
