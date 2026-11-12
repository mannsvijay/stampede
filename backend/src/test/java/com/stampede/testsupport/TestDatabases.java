package com.stampede.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres and one Redis for the whole test JVM, and a separate database per Spring test context.
 * <p>
 * <b>Why one server:</b> a container per test class or per context meant up to 16 Postgres and 5 Redis
 * containers per run, many alive at once, and cached contexts kept pools pointing at containers that
 * had already stopped. Now the containers start on first use and are never stopped by JUnit;
 * Testcontainers' Ryuk container removes them when the JVM exits.
 * <p>
 * <b>Why a database per context:</b> isolation stays exactly what it was with a container per context.
 * Each context's Flyway migrates its own empty database, and {@code InvariantAssertionListener} checks
 * through the context's own DataSource, so it never sees another context's data (deliberate violations
 * of {@code @ExpectInvariantViolations} tests, or a TRUNCATE-per-test class's last leftovers).
 * <p>
 * Redis is shared without isolation: nothing in the app writes keys yet (PLAN note on M2.2).
 */
public final class TestDatabases {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final AtomicInteger APP_CONTEXTS = new AtomicInteger();

    /**
     * Started on first use. Every cached context keeps a pool on this one server, so the connection
     * limit is raised above Postgres's default of 100; test config/application.properties also lets
     * idle pools shrink to zero.
     */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=200");

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private TestDatabases() {
    }

    /**
     * For a {@code @DynamicPropertySource} method: creates the database {@code name} (if it doesn't
     * exist yet) and points {@code spring.datasource.*} at it. One name per test class (or base class)
     * that used to own a container, so its Spring context stays separate from every other.
     */
    public static void register(DynamicPropertyRegistry registry, String name) {
        create(name);
        registry.add("spring.datasource.url", () -> jdbcUrl(name));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A new, empty database for one application context ({@code app_1}, {@code app_2}, ...). */
    static String createForAppContext() {
        String name = "app_" + APP_CONTEXTS.incrementAndGet();
        create(name);
        return name;
    }

    /** Creates the database unless it exists. Synchronized: CREATE DATABASE can't run in parallel on one template. */
    static synchronized void create(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain lowercase database name: " + name);
        }
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            try (PreparedStatement exists = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                exists.setString(1, name);
                try (ResultSet rs = exists.executeQuery()) {
                    if (rs.next()) {
                        return;
                    }
                }
            }
            try (Statement create = admin.createStatement()) {
                create.execute("CREATE DATABASE " + name);  // name checked above; identifiers can't be bound
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
    }

    static String jdbcUrl(String name) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + name;
    }

    static String username() {
        return POSTGRES.getUsername();
    }

    static String password() {
        return POSTGRES.getPassword();
    }

    static String redisHost() {
        return REDIS.getHost();
    }

    static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
