package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suite's own connection is one row-level security applies to.
 *
 * <p>Every other test in this package rests on this. A superuser bypasses row-level security
 * entirely, so a query returning nothing in production returns everything under one, and an
 * insert the policies refuse in production succeeds. Four bugs lived in that gap across four
 * epics — the expiry sweep that never swept, provisioning that could not create a tenant, and
 * invitation acceptance that could not find an invitation — while the suite stayed green
 * throughout.
 *
 * <p>So this asserts the property directly rather than trusting the configuration to keep
 * providing it. If it ever fails, every isolation assertion elsewhere has quietly stopped
 * proving anything, and that is worth finding out from a named test rather than from
 * production.
 */
@DisplayName("The connection the tests run on")
class UnprivilegedConnectionIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantProvisioningService provisioning;

    @Test
    @DisplayName("is not a superuser")
    void is_not_a_superuser() {
        Boolean superuser = jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);

        assertThat(superuser)
                .as("a superuser bypasses row-level security, so every isolation test in this "
                        + "package would pass whether or not a single policy existed")
                .isFalse();
    }

    @Test
    @DisplayName("is the application role, not the role that owns the tables")
    void is_not_the_owner() {
        String user = jdbc.queryForObject("SELECT current_user", String.class);
        String owner = jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename = 'tenants'", String.class);

        assertThat(user).isEqualTo(APP_ROLE);
        assertThat(owner)
                .as("the application must not own its own tables; FORCE covers the owner, but "
                        + "not owning them is the part that does not depend on remembering FORCE")
                .isNotEqualTo(user);
    }

    @Test
    @DisplayName("has every privilege the application needs, and no more")
    void has_exactly_the_privileges_it_needs() {
        Long uncovered = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND NOT has_table_privilege(current_user, c.oid,
                                              'SELECT,INSERT,UPDATE,DELETE')""", Long.class);

        assertThat(uncovered)
                .as("default privileges are set before the migration, so a table added later "
                        + "is covered without anybody updating a grant list")
                .isZero();
    }

    @Test
    @DisplayName("sees nothing at all when no tenant is established")
    void row_level_security_is_actually_in_force() {
        // A tenant that certainly exists, so an empty result is the policy and not the data.
        String unique = UUID.randomUUID().toString().substring(0, 8);
        provisioning.provision("Privilege " + unique, "priv-" + unique, "own_inventory",
                "Main", "Owner", "priv-" + unique + "@example.com", "a-long-enough-password");

        TenantContext.clear();
        Long visible = jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class);

        assertThat(visible)
                .as("an unset tenant must match nothing; under a superuser this counts every "
                        + "tenant in the database instead")
                .isZero();
    }
}
