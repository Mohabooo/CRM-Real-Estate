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
 * <p>Every existing test missed it for one reason: the suite connected as a superuser, and a
 * superuser bypasses row-level security entirely, so the query returned every tenant no
 * matter what the flag said. That is precisely the failure mode V5's own comment warns
 * about, reproduced in the code the comment is attached to.
 *
 * <p>It no longer does. {@code AbstractPostgresIT} connects as an unprivileged role, so the
 * assertions below run with the policies genuinely in force and need no {@code SET ROLE}
 * scaffolding of their own — {@link UnprivilegedConnectionIT} is what guarantees that, in
 * one place, for every test in this package.
 */
@DisplayName("Platform tenant registry")
class PlatformTenantRegistryIT extends AbstractPostgresIT {

    @Autowired
    private PlatformTenantRegistry registry;

    @Autowired
    private TenantProvisioningService provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("finds active tenants where row-level security applies")
    void finds_tenants_under_row_level_security() {
        UUID tenantId = provisionOne();

        // Called exactly as the sweep calls it: no transaction, no tenant established.
        List<UUID> ids = registry.activeTenantIds();

        assertThat(ids)
                .as("the registry must see tenants as an unprivileged role; an empty list "
                        + "here is a sweep that silently never runs in production")
                .contains(tenantId);
    }

    @Test
    @DisplayName("grants nothing beyond the tenant registry: units stay invisible")
    void the_exemption_opens_only_the_registry() {
        provisionOne();

        // One transaction so the SET and the SELECT share a connection; without it they
        // would be two, and the flag would be gone by the time the query ran.
        Long units = transactions.execute(status -> {
            try {
                jdbc.execute("SET app.platform_task = 'reservation-expiry'");
                jdbc.execute("SET app.current_tenant_id = ''");
                return jdbc.queryForObject("SELECT count(*) FROM units", Long.class);
            } finally {
                jdbc.execute("SET app.platform_task = ''");
            }
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
}
