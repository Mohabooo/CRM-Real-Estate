package com.rescrm.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * Base class for integration tests that need a real PostgreSQL.
 *
 * <p>Doc 28 section 14 requires a real database rather than an in-memory substitute: the
 * constraints this platform depends on — partial unique indexes, exclusion constraints,
 * NUMERIC domains, row-level security — either do not exist or behave differently elsewhere.
 * A green test against H2 would be evidence of nothing.
 *
 * <p>That requirement is about the database being real, not about what started it. Three
 * sources are tried in order:
 *
 * <ol>
 *   <li><b>A PostgreSQL you already run</b>, when {@code crm.it.db.url} (or the environment
 *       variable {@code CRM_IT_DB_URL}) is set. The tests never run against the database
 *       named there: it is used only to connect, and a dedicated database is created for the
 *       run and dropped when the JVM exits.</li>
 *   <li><b>Testcontainers</b>, when a Docker daemon is reachable. This is what CI uses.</li>
 *   <li><b>An embedded PostgreSQL 16</b>, started as an ordinary local process on a free
 *       port. No Docker, no installation, no configuration. This is the fallback that keeps
 *       {@code mvn verify} working on a machine where Docker is absent or — as with Docker 29
 *       and the client Testcontainers uses — present but unusable.</li>
 * </ol>
 *
 * <p>Every one of the three is a real PostgreSQL, so the guarantee above holds in all cases.
 *
 * <h2>Two roles, on purpose</h2>
 *
 * <p>Flyway migrates as the owner; the application connects as {@value #APP_ROLE}, which owns
 * nothing and is not a superuser. That split exists because the alternative hid four
 * production bugs across four epics.
 *
 * <p>A superuser bypasses row-level security entirely. So does a table's owner unless the
 * policy is FORCEd — and these are FORCEd, which is why that half held. The superuser half
 * did not: a query that returned nothing in production returned everything here, and an
 * insert that row-level security refused in production succeeded here. The reservation
 * expiry sweep, tenant provisioning and invitation acceptance were all broken that way while
 * the suite stayed green.
 *
 * <p>Privileges reach the application role through {@code ALTER DEFAULT PRIVILEGES}, set
 * before any table exists, so every table Flyway creates afterwards is covered without a
 * grant step that somebody has to remember to update. Verified against PostgreSQL 16: all
 * fourteen tables, none missing.
 *
 * <p>One consequence worth knowing when a test fails oddly. Fixture SQL run straight through
 * {@code JdbcTemplate} is now subject to the same policies as everything else, so an
 * {@code UPDATE} with no tenant established quietly matches no rows rather than doing what it
 * used to. Wrap such setup in {@code TenantContext.callAs(tenantId, ...)}, which is what the
 * application itself does.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractPostgresIT.class);

    /**
     * The role the application connects as: owns nothing, and is not a superuser.
     *
     * <p>Cluster-wide and shared by every test class, created once if it is not already
     * there. The password is fixed and worthless — it protects a database that exists for
     * the length of a build.
     */
    static final String APP_ROLE = "crm_app";

    private static final String APP_PASSWORD = "crm_app";

    private static final String URL_KEY = "crm.it.db.url";
    private static final String USERNAME_KEY = "crm.it.db.username";
    private static final String PASSWORD_KEY = "crm.it.db.password";

    private static final String NO_DATABASE_AVAILABLE = """
            Integration tests need a real PostgreSQL 16 and could not obtain one.

            Neither Docker nor the embedded PostgreSQL could provide one. The embedded
            failure is the exception below; the Docker failure is attached as suppressed.

            To use a PostgreSQL you already run instead:

                mvn verify -Dcrm.it.db.url=jdbc:postgresql://localhost:5432/postgres \\
                           -Dcrm.it.db.username=postgres \\
                           -Dcrm.it.db.password=postgres

            To skip integration tests for a fast local loop: mvn verify -DskipITs
            CI never skips them.
            """;

    private static final Coordinates DATABASE = resolve();

    /** The same database, reached as the unprivileged application role. */
    private static final Coordinates APPLICATION = prepareApplicationRole(DATABASE);

    private record Coordinates(String url, String username, String password) { }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Flyway needs to create tables, triggers and policies, so it keeps the owner's
        // credentials. Nothing else uses them.
        registry.add("spring.flyway.url", DATABASE::url);
        registry.add("spring.flyway.user", DATABASE::username);
        registry.add("spring.flyway.password", DATABASE::password);

        // Everything the application does goes through a role that row-level security
        // actually applies to.
        registry.add("spring.datasource.url", APPLICATION::url);
        registry.add("spring.datasource.username", APPLICATION::username);
        registry.add("spring.datasource.password", APPLICATION::password);
    }

    /**
     * Creates the application role and arranges for it to be granted what it needs.
     *
     * <p>Runs as the owner, before the Spring context and therefore before Flyway. The
     * ordering is the point: {@code ALTER DEFAULT PRIVILEGES} applies to tables created
     * <em>after</em> it, which is every table in the schema. Granting on ALL TABLES instead
     * would need to happen after the migration, which means a callback and a step somebody
     * can forget; this needs neither.
     *
     * <p>The explicit grants that follow cover a database that already had tables when this
     * ran — a developer pointing {@code crm.it.db.url} at one they keep around.
     */
    private static Coordinates prepareApplicationRole(Coordinates owner) {
        String setup = """
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                    CREATE ROLE %s LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                  END IF;
                END $$;
                GRANT USAGE ON SCHEMA public TO %s;
                ALTER DEFAULT PRIVILEGES IN SCHEMA public
                    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %s;
                ALTER DEFAULT PRIVILEGES IN SCHEMA public
                    GRANT USAGE, SELECT ON SEQUENCES TO %s;
                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %s;
                GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %s;
                """.formatted(APP_ROLE, APP_ROLE, APP_PASSWORD, APP_ROLE, APP_ROLE,
                        APP_ROLE, APP_ROLE, APP_ROLE);

        try (Connection connection = DriverManager.getConnection(
                     owner.url(), owner.username(), owner.password());
             Statement statement = connection.createStatement()) {
            statement.execute(setup);
        } catch (SQLException e) {
            throw new IllegalStateException("""
                    Could not create the unprivileged role the integration tests connect as.

                    The tests deliberately do not run as a superuser: one bypasses row-level
                    security entirely, and that has already hidden production bugs. Creating
                    the role needs a connecting user that may CREATE ROLE.

                    If you are pointing crm.it.db.url at your own PostgreSQL, use an account
                    that can. Docker and the embedded server both provide one.
                    """, e);
        }
        return new Coordinates(owner.url(), APP_ROLE, APP_PASSWORD);
    }

    private static Coordinates resolve() {
        String url = setting(URL_KEY);
        if (url != null && !url.isBlank()) {
            return throwawayDatabaseOn(
                    url.trim(),
                    orDefault(setting(USERNAME_KEY), "crm"),
                    orDefault(setting(PASSWORD_KEY), "crm"));
        }
        try {
            return containerDatabase();
        } catch (RuntimeException dockerUnavailable) {
            log.info("Docker is not usable for tests ({}); falling back to embedded PostgreSQL",
                    rootMessage(dockerUnavailable));
            return embeddedDatabase(dockerUnavailable);
        }
    }

    /** System property first, then the shouty environment-variable spelling of the same key. */
    private static String setting(String key) {
        String fromProperty = System.getProperty(key);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        return System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_'));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static Coordinates containerDatabase() {
        PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:16-alpine")
                        .withDatabaseName("crm_test")
                        .withUsername("crm_test")
                        .withPassword("crm_test");
        postgres.start();
        log.info("Integration tests are using a Testcontainers PostgreSQL");
        return new Coordinates(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Coordinates embeddedDatabase(RuntimeException dockerUnavailable) {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(postgres), "stop-embedded-postgres"));
            log.info("Integration tests are using an embedded PostgreSQL on port {}", postgres.getPort());
            return new Coordinates(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
        } catch (IOException | RuntimeException embeddedFailed) {
            IllegalStateException failure = new IllegalStateException(NO_DATABASE_AVAILABLE, embeddedFailed);
            failure.addSuppressed(dockerUnavailable);
            throw failure;
        }
    }

    private static void stop(EmbeddedPostgres postgres) {
        try {
            postgres.close();
        } catch (IOException e) {
            log.warn("Could not stop the embedded PostgreSQL; it exits with the JVM anyway", e);
        }
    }

    private static Coordinates throwawayDatabaseOn(String adminUrl, String username, String password) {
        // Generated here, hexadecimal only, so it is never attacker-influenced input.
        String database = "crm_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String url = withDatabase(adminUrl, database);

        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not create the integration-test database on " + adminUrl
                            + " as user '" + username + "'. Is it running, and may that user "
                            + "create databases? Unset crm.it.db.url to use the embedded "
                            + "PostgreSQL instead, which needs nothing running.", e);
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> drop(adminUrl, username, password, database), "drop-" + database));
        log.info("Integration tests are using throwaway database {}", database);
        return new Coordinates(url, username, password);
    }

    private static void drop(String adminUrl, String username, String password, String database) {
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            // FORCE terminates connections the test context has not released yet; without it
            // the drop loses a race with the pool's own shutdown and leaves databases behind.
            statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
        } catch (SQLException e) {
            log.warn("Could not drop throwaway database {}; drop it by hand if it lingers", database, e);
        }
    }

    /**
     * Replaces the database segment of a JDBC URL, preserving any query parameters.
     * {@code jdbc:postgresql://localhost:5432/crm?ssl=false} becomes
     * {@code jdbc:postgresql://localhost:5432/<database>?ssl=false}.
     */
    private static String withDatabase(String jdbcUrl, String database) {
        int query = jdbcUrl.indexOf('?');
        String base = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        String parameters = query < 0 ? "" : jdbcUrl.substring(query);

        int authority = base.indexOf("//");
        int lastSlash = base.lastIndexOf('/');
        if (authority < 0 || lastSlash <= authority + 1) {
            throw new IllegalArgumentException(
                    "Expected a JDBC URL naming a database, such as "
                            + "jdbc:postgresql://localhost:5432/crm, but got: " + jdbcUrl);
        }
        return base.substring(0, lastSlash + 1) + database + parameters;
    }
}
