package com.rescrm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that migrations apply cleanly and that Epic 0 created the foundation — and only
 * the foundation.
 */
@DisplayName("Flyway migrations")
class FlywayMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V1 applied successfully")
    void baseline_migration_applied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(versions).contains("1");
    }

    @Test
    @DisplayName("the money and rate domains exist with the documented precision")
    void money_domains_exist() {
        Integer moneyPrecision = jdbc.queryForObject(
                "SELECT numeric_precision FROM information_schema.domains WHERE domain_name = 'money_amount'",
                Integer.class);
        Integer moneyScale = jdbc.queryForObject(
                "SELECT numeric_scale FROM information_schema.domains WHERE domain_name = 'money_amount'",
                Integer.class);
        Integer rateScale = jdbc.queryForObject(
                "SELECT numeric_scale FROM information_schema.domains WHERE domain_name = 'rate_percentage'",
                Integer.class);

        assertThat(moneyPrecision).isEqualTo(18);
        assertThat(moneyScale).isEqualTo(2);
        assertThat(rateScale).isEqualTo(4);
    }

    @Test
    @DisplayName("the money domain rejects excess precision at the database level")
    void money_domain_enforces_scale() {
        jdbc.execute("CREATE TEMP TABLE money_domain_probe (amount money_amount)");

        // PostgreSQL rounds to the declared scale rather than erroring, which is itself worth
        // pinning down: the application must never rely on the database to reject over-precise
        // input, because it will not. Money.of() is where that rejection happens.
        jdbc.update("INSERT INTO money_domain_probe VALUES (?)", new BigDecimal("1.005"));
        BigDecimal stored = jdbc.queryForObject(
                "SELECT amount FROM money_domain_probe", BigDecimal.class);

        assertThat(stored).isEqualByComparingTo("1.01");
        assertThat(stored.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("only Epic 1's tables exist; nothing from a later epic has leaked in")
    void only_epic_one_tables_exist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);

        // Updated for Epic 1, which owns exactly these five. The test keeps its original
        // purpose: catching a later epic's work leaking into this one.
        assertThat(tables).containsExactlyInAnyOrder(
                "flyway_schema_history",
                "tenants", "branches", "users", "invitations", "audit_events");

        assertThat(tables)
                .as("no later epic's table may appear before its epic")
                .doesNotContain("leads", "customers", "activities", "tasks",
                        "developers", "projects", "phases", "units", "reservations", "deals",
                        "payment_plan_templates", "customer_payment_plans", "installments",
                        "payments", "payment_allocations", "commissions", "commission_rules");
    }

    @Test
    @DisplayName("every Epic 1 table enforces row-level security, owner included")
    void every_table_forces_row_level_security() {
        List<String> unprotected = jdbc.queryForList(
                "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relkind = 'r' "
                        + "AND relname IN ('tenants','branches','users','invitations','audit_events') "
                        + "AND (c.relrowsecurity = false OR c.relforcerowsecurity = false)",
                String.class);

        // FORCE matters as much as ENABLE: without it the owning role bypasses its own
        // policies, and in development that role is the one the application connects as.
        assertThat(unprotected).isEmpty();
    }

    @Test
    @DisplayName("the tenant session variable behaves as the RLS design assumes")
    void tenant_session_variable_semantics() {
        // V1 recorded the convention; Epic 1's TenantAwareDataSource now enforces it by
        // stamping every connection it hands out. So the variable is never unset any more,
        // and this no longer raises. What RLS actually depends on is the empty case
        // resolving to NULL, which the policies treat as matching no rows rather than all.
        String whenNoTenantEstablished = jdbc.queryForObject(
                "SELECT current_setting('app.current_tenant_id', true)", String.class);
        assertThat(whenNoTenantEstablished).isNullOrEmpty();

        jdbc.execute("SET app.current_tenant_id = ''");
        assertThat(jdbc.queryForObject(
                "SELECT nullif(current_setting('app.current_tenant_id', true), '')::uuid IS NULL",
                Boolean.class))
                .as("an empty binding must match nothing, not everything")
                .isTrue();

        jdbc.execute("SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111'");
        String value = jdbc.queryForObject(
                "SELECT current_setting('app.current_tenant_id')", String.class);
        assertThat(value).isEqualTo("11111111-1111-1111-1111-111111111111");
    }
}
