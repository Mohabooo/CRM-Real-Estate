package com.rescrm.platform.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Binds the current tenant to the database session, so PostgreSQL row-level security has
 * something to match on (doc 22 section 4, doc 28 section 4).
 *
 * <p>Every connection handed out is stamped, including when no tenant is established — in
 * that case the setting is cleared to the empty string, which the policies treat as "matches
 * nothing". Setting it unconditionally is what makes this safe: a pooled connection can never
 * carry the previous borrower's tenant into the next request, because the next borrower
 * overwrites it before running anything.
 *
 * <p>The cost is one extra round trip per connection acquisition. That is the price of the
 * database enforcing isolation itself rather than trusting the application to remember.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final String SET_TENANT_SQL = "SELECT set_config('app.current_tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bindTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bindTenant(super.getConnection(username, password));
    }

    private Connection bindTenant(Connection connection) throws SQLException {
        Optional<UUID> tenantId = TenantContext.current();
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, tenantId.map(UUID::toString).orElse(""));
            statement.execute();
        } catch (SQLException e) {
            // Never hand out a connection whose tenant binding is unknown: it would read
            // under whatever the previous borrower left behind.
            connection.close();
            throw e;
        }
        return connection;
    }
}
