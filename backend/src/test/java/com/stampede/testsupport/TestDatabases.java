package com.stampede.testsupport;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container;
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
 * <b>Redis: one server, a key prefix per context</b> (M2.2a, D-022). Every inventory key starts with
 * {@code stampede.inventory.redis.key-prefix}; each context gets its own prefix ({@link #registerRedis}
 * for slices, {@link #newAppKeyPrefix} for full application contexts), so no context sees another's
 * holds. Not a Redis database number per context: there are only 16. Tests delete their own keys with
 * {@link TestRedis#deleteKeys}, never FLUSHDB.
 */
public final class TestDatabases {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final AtomicInteger APP_CONTEXTS = new AtomicInteger();
    private static final AtomicInteger APP_KEY_PREFIXES = new AtomicInteger();

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

    /**
     * For a {@code @DynamicPropertySource} method of a test that uses Redis without the full
     * application: points {@code spring.data.redis.*} at the shared server and makes {@code name}
     * the context's inventory key prefix. One name per test class (or base class), like
     * {@link #register}.
     */
    public static void registerRedis(DynamicPropertyRegistry registry, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain lowercase key prefix: " + name);
        }
        registry.add("spring.data.redis.host", TestDatabases::redisHost);
        registry.add("spring.data.redis.port", TestDatabases::redisPort);
        registry.add("spring.data.redis.password", () -> "");  // the test Redis has none, whatever .env says
        registry.add("stampede.inventory.redis.key-prefix", () -> name);
    }

    /** A new inventory key prefix for one application context ({@code app_keys_1}, ...). */
    static String newAppKeyPrefix() {
        return "app_keys_" + APP_KEY_PREFIXES.incrementAndGet();
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

    public static String username() {
        return POSTGRES.getUsername();
    }

    /** Runs a command inside the Postgres container (SecretsAtRestTest's pg_dump). */
    public static Container.ExecResult execInPostgres(String... command) throws IOException, InterruptedException {
        return POSTGRES.execInContainer(command);
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
