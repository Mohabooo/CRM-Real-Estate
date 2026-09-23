package com.rescrm.platform.tenancy;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one way to find out which tenants exist, for work that runs without a tenant.
 *
 * <p>Scheduled work has a bootstrapping problem: row-level security needs a tenant to be
 * established before anything is visible, and a sweep has no tenant until it knows which
 * ones exist. V5 answers it with a single narrow policy — with {@code app.platform_task}
 * set to the expected value, the tenant REGISTRY becomes readable, for SELECT, and nothing
 * else changes anywhere. Units, leads, reservations and every other table stay exactly as
 * isolated as they were.
 *
 * <p>Both statements run on one connection deliberately. {@code TenantAwareDataSource}
 * clears the flag on every connection it hands out, so setting it has to happen on the same
 * connection that then reads — which also means the flag cannot outlive this method or leak
 * into a pooled connection a request later borrows.
 *
 * <p>Returns ids and nothing else. A caller that wants anything about a tenant reads it
 * under that tenant, through the ordinary policy, like everybody else.
 */
@Component
public class PlatformTenantRegistry {

    /** Matches the policy in V5 exactly; any other value grants nothing. */
    static final String PLATFORM_TASK = "reservation-expiry";

    private final JdbcTemplate jdbc;

    public PlatformTenantRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The ids of every active tenant, for a sweep to then run against one at a time. */
    public List<UUID> activeTenantIds() {
        return jdbc.execute((ConnectionCallback<List<UUID>>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.platform_task = '" + PLATFORM_TASK + "'");
                try (ResultSet rs = statement.executeQuery(
                        "SELECT id FROM tenants WHERE status = 'active' ORDER BY created_at")) {
                    List<UUID> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                    return ids;
                }
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("SET app.platform_task = ''");
                }
            }
        });
    }
}
