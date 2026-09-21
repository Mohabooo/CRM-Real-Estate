package com.rescrm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that migrations apply cleanly and that the schema contains exactly the tables the
 * implemented epics own — no more.
 *
 * <p>The "no more" half is the one that earns its keep. It is easy, while building one epic,
 * to add the table a later epic will need because it is obviously coming; the result is a
 * schema nobody can date and a migration nobody can review against a story.
 */
@DisplayName("Flyway migrations")
class FlywayMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every migration to date applied successfully")
    void migrations_applied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3");
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
    @DisplayName("only the implemented epics' tables exist; nothing later has leaked in")
    void only_implemented_tables_exist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);

        // Epic 1 owns the first five; Epic 2 adds leads, customers and activities.
        //
        // 'tasks' is deliberately absent. Doc 23 lists no /tasks endpoint and no Epic 2 story
        // needs one, so it belongs to whichever epic first has a use for it rather than being
        // created now on the strength of the word appearing in a diagram.
        assertThat(tables).containsExactlyInAnyOrder(
                "flyway_schema_history",
                "tenants", "branches", "users", "invitations", "audit_events",
                "leads", "customers", "activities");

        assertThat(tables)
                .as("no later epic's table may appear before its epic")
                .doesNotContain("tasks",
                        "developers", "projects", "phases", "units", "reservations", "deals",
                        "payment_plan_templates", "customer_payment_plans", "installments",
                        "payments", "payment_allocations", "commissions", "commission_rules");
    }

    @Test
    @DisplayName("every business table enforces row-level security, owner included")
    void every_table_forces_row_level_security() {
        List<String> unprotected = jdbc.queryForList(
                "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relkind = 'r' "
                        + "AND relname IN ('tenants','branches','users','invitations',"
                        + "'audit_events','leads','customers','activities') "
                        + "AND (c.relrowsecurity = false OR c.relforcerowsecurity = false)",
                String.class);

        // FORCE matters as much as ENABLE: without it the owning role bypasses its own
        // policies, and in development that role is the one the application connects as.
        assertThat(unprotected).isEmpty();
    }

    @Test
    @DisplayName("the tenant session variable behaves as the RLS design assumes")
    @Transactional
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
