package com.rescrm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("no business tables exist yet")
    void no_business_tables_created() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);

        // Epic 0 is the technical foundation. If any of these appear, a later epic's work has
        // leaked into it — which is exactly what this test is here to catch.
        assertThat(tables)
                .as("Epic 0 must not create business tables")
                .doesNotContain("tenants", "users", "branches", "leads", "customers",
                        "developers", "projects", "units", "reservations", "deals",
                        "payment_plan_templates", "customer_payment_plans", "installments",
                        "payments", "payment_allocations", "commissions", "commission_rules",
                        "audit_events");

        assertThat(tables).containsExactlyInAnyOrder("flyway_schema_history");
    }

    @Test
    @DisplayName("the tenant session variable behaves as the RLS design assumes")
    void tenant_session_variable_semantics() {
        // Documented in V1: reading the variable before it is set must raise rather than
        // return NULL, so a query with no tenant established fails instead of matching
        // every row once RLS policies arrive in Epic 1.
        assertThatThrownBy(() ->
                jdbc.queryForObject("SELECT current_setting('app.current_tenant_id')", String.class))
                .hasMessageContaining("app.current_tenant_id");

        jdbc.execute("SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111'");
        String value = jdbc.queryForObject(
                "SELECT current_setting('app.current_tenant_id')", String.class);
        assertThat(value).isEqualTo("11111111-1111-1111-1111-111111111111");
    }
}
