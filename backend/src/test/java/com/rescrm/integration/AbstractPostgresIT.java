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
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractPostgresIT.class);

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

    private record Coordinates(String url, String username, String password) { }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
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
