package com.rescrm.integration;

import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the database enforces tenant isolation on its own, with the application out of the
 * picture entirely.
 *
 * <p>Every statement below runs as a dedicated non-superuser role. That detail is the whole
 * test: PostgreSQL exempts superusers from row-level security by design, and both the
 * embedded server and the Testcontainers image connect as one. An RLS assertion made as a
 * superuser passes whether or not a single policy exists, which is worse than no test at all.
 *
 * <p>{@link IdentityTenantIsolationIT} covers the other layer. Doc 22 section 4 requires both
 * and trusts neither alone.
 */
@DisplayName("Row-level security")
class RlsIsolationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantProvisioningService provisioning;

    private record TwoTenants(UUID a, UUID b, UUID branchOfB) {
    }

    private TwoTenants provisionTwo() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var a = provisioning.provision("RLS A " + unique, "own_inventory", "A", "A",
                "rls-a-" + unique + "@example.com", "a-long-enough-password");
        var b = provisioning.provision("RLS B " + unique, "own_inventory", "B", "B",
                "rls-b-" + unique + "@example.com", "a-long-enough-password");
        return new TwoTenants(a.tenant().id(), b.tenant().id(), b.initialBranch().id());
    }

    /**
     * Runs the body on one connection, bound to one tenant.
     *
     * <p>It used to switch role as well, creating an unprivileged one on the way in, because
     * the suite connected as a superuser and a superuser bypasses row-level security — so
     * without the switch none of the assertions below meant anything. {@code
     * AbstractPostgresIT} now connects as an unprivileged role to begin with, so all that is
     * left to do here is bind the tenant. {@link UnprivilegedConnectionIT} is what keeps the
     * premise true.
     *
     * <p>One connection still matters: the tenant binding is a session setting, so the body
     * has to run on the connection it was set on.
     */
    private <T> T asUnprivilegedRole(UUID tenantId, SqlBody<T> body) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            try (Statement setup = connection.createStatement()) {
                setup.execute("SET app.current_tenant_id = '" + tenantId + "'");
            }
            try {
                return body.run(connection);
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("RESET app.current_tenant_id");
                }
            }
        });
    }

    @FunctionalInterface
    private interface SqlBody<T> {
        T run(Connection connection) throws SQLException;
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("the connection really is not a superuser, or nothing below proves anything")
    void probe_role_is_not_superuser() {
        var tenants = provisionTwo();
        boolean isSuper = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
                rs.next();
                return rs.getBoolean(1);
            }
        });
        assertThat(isSuper).isFalse();
    }

    @Test
    @DisplayName("tenant A sees none of tenant B's rows")
    void cross_tenant_reads_return_nothing() {
        var tenants = provisionTwo();
        asUnprivilegedRole(tenants.a(), connection -> {
            assertThat(count(connection,
                    "SELECT count(*) FROM branches WHERE tenant_id = '" + tenants.b() + "'"))
                    .isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM users WHERE tenant_id = '" + tenants.b() + "'"))
                    .isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM tenants WHERE id = '" + tenants.b() + "'"))
                    .isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM audit_events WHERE tenant_id = '" + tenants.b() + "'"))
                    .isZero();
            // Its own rows are still visible, so the policy is filtering rather than denying.
            assertThat(count(connection,
                    "SELECT count(*) FROM branches WHERE tenant_id = '" + tenants.a() + "'"))
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("a cross-tenant UPDATE changes nothing")
    void cross_tenant_update_affects_no_rows() {
        var tenants = provisionTwo();
        int updated = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(
                        "UPDATE branches SET name = 'taken over' WHERE id = '"
                                + tenants.branchOfB() + "'");
            }
        });
        assertThat(updated).isZero();

        String nameInB = TenantContext.callAs(tenants.b(), () -> jdbc.queryForObject(
                "SELECT name FROM branches WHERE id = ?", String.class, tenants.branchOfB()));
        assertThat(nameInB).isEqualTo("B");
    }

    @Test
    @DisplayName("a cross-tenant DELETE removes nothing")
    void cross_tenant_delete_affects_no_rows() {
        var tenants = provisionTwo();
        int deleted = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(
                        "DELETE FROM branches WHERE id = '" + tenants.branchOfB() + "'");
            }
        });
        assertThat(deleted).isZero();

        Long stillThere = TenantContext.callAs(tenants.b(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM branches WHERE id = ?", Long.class, tenants.branchOfB()));
        assertThat(stillThere).isEqualTo(1);
    }

    @Test
    @DisplayName("an INSERT into another tenant is refused by the WITH CHECK policy")
    void cross_tenant_insert_is_refused() {
        var tenants = provisionTwo();
        String failure = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO branches (tenant_id, name) VALUES ('"
                        + tenants.b() + "', 'smuggled')");
                return null;
            } catch (SQLException e) {
                return e.getMessage();
            }
        });
        assertThat(failure).contains("row-level security");
    }

    @Test
    @DisplayName("Epic 2's tables are isolated by the database too")
    void crm_tables_are_isolated() {
        var tenants = provisionTwo();

        // Inserted outside the probe, so both rows genuinely exist. The connection the
        // application uses in tests is a superuser and therefore exempt from RLS, which is
        // exactly why the assertions below are made as the probe role instead.
        UUID leadOfA = insertLead(tenants.a(), "Lead in A", "+201000000001");
        UUID leadOfB = insertLead(tenants.b(), "Lead in B", "+201000000002");
        UUID customerOfB = insertCustomer(tenants.b(), "Customer in B", "+201000000003");
        insertActivity(tenants.b(), leadOfB);

        asUnprivilegedRole(tenants.a(), connection -> {
            assertThat(count(connection,
                    "SELECT count(*) FROM leads WHERE id = '" + leadOfB + "'")).isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM customers WHERE id = '" + customerOfB + "'")).isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM activities WHERE subject_id = '" + leadOfB + "'"))
                    .isZero();

            // A's own lead is still visible, so the policy filters rather than denying.
            assertThat(count(connection,
                    "SELECT count(*) FROM leads WHERE id = '" + leadOfA + "'")).isEqualTo(1);
            return null;
        });

        // A cross-tenant write changes nothing and raises nothing: RLS makes B's rows
        // invisible, and an UPDATE cannot touch what it cannot see.
        int updated = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(
                        "UPDATE leads SET name = 'taken over' WHERE id = '" + leadOfB + "'");
            }
        });
        assertThat(updated).isZero();
        assertThat(TenantContext.callAs(tenants.b(), () -> jdbc.queryForObject(
                "SELECT name FROM leads WHERE id = ?", String.class, leadOfB)))
                .isEqualTo("Lead in B");

        int deleted = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(
                        "DELETE FROM customers WHERE id = '" + customerOfB + "'");
            }
        });
        assertThat(deleted).isZero();
        assertThat(TenantContext.callAs(tenants.b(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM customers WHERE id = ?", Long.class, customerOfB)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a lead cannot be smuggled into another tenant by naming its id")
    void cross_tenant_lead_insert_is_refused() {
        var tenants = provisionTwo();
        String failure = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO leads (tenant_id, name, phone, phone_normalized, stage, "
                                + "status) VALUES ('" + tenants.b() + "', 'smuggled', "
                                + "'+201000000009', '+201000000009', 'new', 'active')");
                return null;
            } catch (SQLException e) {
                return e.getMessage();
            }
        });
        assertThat(failure).contains("row-level security");
    }

    private UUID insertLead(UUID tenantId, String name, String phone) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO leads (tenant_id, name, phone, phone_normalized, stage, status) "
                        + "VALUES (?, ?, ?, ?, 'new', 'active') RETURNING id",
                UUID.class, tenantId, name, phone, phone));
    }

    private UUID insertCustomer(UUID tenantId, String name, String phone) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO customers (tenant_id, name_en, phone, phone_normalized, status) "
                        + "VALUES (?, ?, ?, ?, 'active') RETURNING id",
                UUID.class, tenantId, name, phone, phone));
    }

    private void insertActivity(UUID tenantId, UUID leadId) {
        TenantContext.callAs(tenantId, () -> jdbc.update(
                "INSERT INTO activities (tenant_id, subject_type, subject_id, type, body, "
                        + "occurred_at) VALUES (?, 'Lead', ?, 'call', 'spoke', now())",
                tenantId, leadId));
    }

    @Test
    @DisplayName("Epic 3's inventory tables are isolated by the database too")
    void inventory_tables_are_isolated() {
        var tenants = provisionTwo();

        UUID projectOfA = insertProject(tenants.a(), "Project A");
        UUID projectOfB = insertProject(tenants.b(), "Project B");
        UUID unitOfA = insertUnit(tenants.a(), projectOfA, "A-1");
        UUID unitOfB = insertUnit(tenants.b(), projectOfB, "B-1");
        UUID developerOfB = insertDeveloper(tenants.b(), "Developer B");

        asUnprivilegedRole(tenants.a(), connection -> {
            assertThat(count(connection,
                    "SELECT count(*) FROM projects WHERE id = '" + projectOfB + "'")).isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM units WHERE id = '" + unitOfB + "'")).isZero();
            assertThat(count(connection,
                    "SELECT count(*) FROM developers WHERE id = '" + developerOfB + "'"))
                    .isZero();

            // A's own rows remain visible, so the policy filters rather than denying.
            assertThat(count(connection,
                    "SELECT count(*) FROM units WHERE id = '" + unitOfA + "'")).isEqualTo(1);
            return null;
        });

        // The unit that matters most: a cross-tenant UPDATE on a unit's status is how one
        // tenant would take another's inventory out of contention.
        int updated = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(
                        "UPDATE units SET status = 'sold' WHERE id = '" + unitOfB + "'");
            }
        });
        assertThat(updated).isZero();
        assertThat(TenantContext.callAs(tenants.b(), () -> jdbc.queryForObject(
                "SELECT status FROM units WHERE id = ?", String.class, unitOfB)))
                .isEqualTo("available");
    }

    @Test
    @DisplayName("a project cannot be smuggled into another tenant")
    void cross_tenant_project_insert_is_refused() {
        var tenants = provisionTwo();
        String failure = asUnprivilegedRole(tenants.a(), connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO projects (tenant_id, commercial_model, name_en, status) "
                                + "VALUES ('" + tenants.b() + "', 'own_inventory', 'smuggled', "
                                + "'draft')");
                return null;
            } catch (SQLException e) {
                return e.getMessage();
            }
        });
        assertThat(failure).contains("row-level security");
    }

    private UUID insertProject(UUID tenantId, String name) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO projects (tenant_id, commercial_model, name_en, status) "
                        + "VALUES (?, 'own_inventory', ?, 'active') RETURNING id",
                UUID.class, tenantId, name));
    }

    private UUID insertUnit(UUID tenantId, UUID projectId, String code) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO units (tenant_id, project_id, code, list_price, status) "
                        + "VALUES (?, ?, ?, 2500000, 'available') RETURNING id",
                UUID.class, tenantId, projectId, code));
    }

    private UUID insertDeveloper(UUID tenantId, String name) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO developers (tenant_id, name) VALUES (?, ?) RETURNING id",
                UUID.class, tenantId, name));
    }

    @Test
    @DisplayName("with no tenant established, nothing is visible at all")
    void unset_tenant_sees_nothing() {
        provisionTwo();
        long visible = jdbc.execute((ConnectionCallback<Long>) connection -> {
            try (Statement setup = connection.createStatement()) {
                setup.execute("SET app.current_tenant_id = ''");
            }
            try {
                // The safe failure: a query with no tenant returns nothing, not everything.
                return count(connection, "SELECT count(*) FROM branches");
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("RESET app.current_tenant_id");
                }
            }
        });
        assertThat(visible).isZero();
    }
}
