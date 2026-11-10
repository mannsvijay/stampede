package com.stampede.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Checks the Flyway schema (V1 to V3) against a fresh Postgres container.
 *
 * <p>{@code @JdbcTest} starts only the JDBC + Flyway slice, and wraps each test in a transaction that
 * is rolled back, so every test builds its own fixture and leaves nothing behind. A failed statement
 * aborts the Postgres transaction, so each test triggers at most one expected failure, as its last step.
 *
 * <p>Constraint failures are asserted by SQLState <em>and</em> constraint name, so a test can't pass
 * because some other constraint (a FK, a PK) happened to fire instead.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SchemaMigrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String RAISE_EXCEPTION = "P0001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Flyway flyway;

    // -------------------------------------------------------------------------------------------
    // Migrations
    // -------------------------------------------------------------------------------------------

    @Test
    void migrationsApplyCleanlyOnFreshDatabase() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3");
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));

        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """).query(String.class).list();
        assertThat(tables).containsExactlyInAnyOrder(
                "flyway_schema_history",
                "users", "devices", "venues", "sections", "seats", "events", "price_tiers",
                "event_seats", "holds", "orders", "order_items", "payments", "idempotency_keys",
                "outbox", "processed_events", "tickets", "checkins", "audit_log",
                "user_event_allowance", "refresh_token_families", "refresh_tokens");
    }

    // -------------------------------------------------------------------------------------------
    // Unique partial index on order_items(event_id, seat_id) WHERE active
    // -------------------------------------------------------------------------------------------

    @Test
    void secondActiveOrderItemForSameSeatIsRejected() {
        Fixture f = fixture();
        UUID firstOrder = insertOrder(f, "PENDING");
        UUID secondOrder = insertOrder(f, "PENDING");  // a different order, so the PK can't be what fires
        insertOrderItem(firstOrder, f, true);

        assertViolates(() -> insertOrderItem(secondOrder, f, true),
                UNIQUE_VIOLATION, "uq_order_items_active_seat");
    }

    @Test
    void sameSeatIsAllowedOnceTheFirstOrderItemIsInactive() {
        Fixture f = fixture();
        UUID firstOrder = insertOrder(f, "CANCELLED");
        UUID secondOrder = insertOrder(f, "PENDING");
        insertOrderItem(firstOrder, f, true);
        jdbc.sql("UPDATE order_items SET active = false WHERE order_id = ?").param(firstOrder).update();

        insertOrderItem(secondOrder, f, true);

        Integer activeForSeat = jdbc.sql("""
                SELECT count(*) FROM order_items WHERE event_id = ? AND seat_id = ? AND active
                """).params(f.eventId, f.seatId).query(Integer.class).single();
        assertThat(activeForSeat).isEqualTo(1);
    }

    // -------------------------------------------------------------------------------------------
    // V3: orders.hold_id (D-017)
    // -------------------------------------------------------------------------------------------

    @Test
    void secondOrderForTheSameHoldIsRejected() {
        Fixture f = fixture();
        UUID holdId = insertHold(f.userId, f.eventId, f.seatId);
        insertOrderFor(f, holdId, "CANCELLED");

        // Even after the first order was cancelled: a hold never gets a second order.
        assertViolates(() -> insertOrderFor(f, holdId, "PENDING"), UNIQUE_VIOLATION, "uq_orders_hold");
    }

    @Test
    void orderWithoutAHoldIsRejected() {
        Fixture f = fixture();
        // A NOT NULL violation names the column, not a constraint.
        assertViolates(() -> jdbc.sql("""
                        INSERT INTO orders (id, user_id, event_id, status, total_paise, saga_state)
                        VALUES (?, ?, ?, 'PENDING', 250000, 'ORDER_CREATED')
                        """).params(UUID.randomUUID(), f.userId, f.eventId).update(),
                NOT_NULL_VIOLATION, "hold_id");
    }

    @Test
    void orderWithAHoldOfAnotherEventIsRejected() {
        Fixture f = fixture();
        Fixture other = fixture();
        UUID otherEventsHold = insertHold(other.userId, other.eventId, other.seatId);

        assertViolates(() -> insertOrderFor(f, otherEventsHold, "PENDING"), FOREIGN_KEY_VIOLATION, "fk_orders_hold");
    }

    // -------------------------------------------------------------------------------------------
    // CHECK constraints: status values and money
    // -------------------------------------------------------------------------------------------

    @Test
    void invalidEventSeatStatusIsRejected() {
        Fixture f = fixture();
        assertViolates(() -> jdbc.sql("UPDATE event_seats SET status = 'BOGUS' WHERE event_id = ? AND seat_id = ?")
                        .params(f.eventId, f.seatId).update(),
                CHECK_VIOLATION, "ck_event_seats_status");
    }

    @Test
    void checkedInIsNoLongerASeatStatus() {
        // M0.2 decision: CHECKED_IN belongs to tickets, not event_seats.
        Fixture f = fixture();
        assertViolates(() -> jdbc.sql("UPDATE event_seats SET status = 'CHECKED_IN' WHERE event_id = ? AND seat_id = ?")
                        .params(f.eventId, f.seatId).update(),
                CHECK_VIOLATION, "ck_event_seats_status");
    }

    @Test
    void invalidOrderStatusIsRejected() {
        Fixture f = fixture();
        assertViolates(() -> insertOrder(f, "SHIPPED"), CHECK_VIOLATION, "ck_orders_status");
    }

    @Test
    void heldSeatWithoutHoldIdIsRejected() {
        Fixture f = fixture();
        assertViolates(() -> jdbc.sql("UPDATE event_seats SET status = 'HELD' WHERE event_id = ? AND seat_id = ?")
                        .params(f.eventId, f.seatId).update(),
                CHECK_VIOLATION, "ck_event_seats_status_pointers");
    }

    @Test
    void negativeTierPriceIsRejected() {
        Fixture f = fixture();
        assertViolates(() -> jdbc.sql("UPDATE price_tiers SET price_paise = -1 WHERE id = ?").param(f.tierId).update(),
                CHECK_VIOLATION, "ck_price_tiers_price_nonnegative");
    }

    @Test
    void negativeOrderItemPriceIsRejected() {
        Fixture f = fixture();
        UUID order = insertOrder(f, "PENDING");
        assertViolates(() -> jdbc.sql("""
                        INSERT INTO order_items (order_id, event_id, seat_id, price_paise, active)
                        VALUES (?, ?, ?, -1, true)
                        """).params(order, f.eventId, f.seatId).update(),
                CHECK_VIOLATION, "ck_order_items_price_nonnegative");
    }

    // -------------------------------------------------------------------------------------------
    // V2: users.role and refresh_tokens
    // -------------------------------------------------------------------------------------------

    @Test
    void newUsersDefaultToRoleUser() {
        Fixture f = fixture();
        assertThat(jdbc.sql("SELECT role FROM users WHERE id = ?").param(f.userId).query(String.class).single())
                .isEqualTo("USER");
    }

    @Test
    void invalidUserRoleIsRejected() {
        Fixture f = fixture();
        assertViolates(() -> jdbc.sql("UPDATE users SET role = 'SUPERUSER' WHERE id = ?").param(f.userId).update(),
                CHECK_VIOLATION, "ck_users_role");
    }

    @Test
    void refreshTokenHashMustBeUnique() {
        UUID family = insertFamily(fixture().userId);
        insertRefreshToken(family, new byte[32]);
        assertViolates(() -> insertRefreshToken(family, new byte[32]),
                UNIQUE_VIOLATION, "uq_refresh_tokens_token_hash");
    }

    @Test
    void refreshTokenHashMustBe32Bytes() {
        UUID family = insertFamily(fixture().userId);
        assertViolates(() -> insertRefreshToken(family, new byte[16]),
                CHECK_VIOLATION, "ck_refresh_tokens_hash_length");
    }

    @Test
    void refreshTokenMustExpireAfterIssue() {
        UUID family = insertFamily(fixture().userId);
        UUID id = insertRefreshToken(family, new byte[32]);
        assertViolates(() -> jdbc.sql("UPDATE refresh_tokens SET expires_at = issued_at WHERE id = ?").param(id).update(),
                CHECK_VIOLATION, "ck_refresh_tokens_expiry");
    }

    @Test
    void unusedRefreshTokenCannotHaveAChild() {
        UUID family = insertFamily(fixture().userId);
        UUID parent = insertRefreshToken(family, new byte[32]);
        byte[] otherHash = new byte[32];
        otherHash[0] = 1;
        UUID child = insertRefreshToken(family, otherHash);
        assertViolates(() -> jdbc.sql("UPDATE refresh_tokens SET replaced_by = ? WHERE id = ?").params(child, parent).update(),
                CHECK_VIOLATION, "ck_refresh_tokens_replaced_only_if_used");
    }

    @Test
    void refreshTokenFamilyRevokedAtAndReasonGoTogether() {
        UUID family = insertFamily(fixture().userId);
        assertViolates(() -> jdbc.sql("UPDATE refresh_token_families SET revoked_at = now() WHERE id = ?")
                        .param(family).update(),
                CHECK_VIOLATION, "ck_refresh_token_families_revoked_consistent");
    }

    @Test
    void refreshTokenFamilyRevokeReasonIsChecked() {
        UUID family = insertFamily(fixture().userId);
        assertViolates(() -> jdbc.sql("UPDATE refresh_token_families SET revoked_at = now(), revoke_reason = 'BORED' WHERE id = ?")
                        .param(family).update(),
                CHECK_VIOLATION, "ck_refresh_token_families_revoke_reason");
    }

    @Test
    void refreshTokenFamilyMustExpireAfterCreation() {
        UUID family = insertFamily(fixture().userId);
        assertViolates(() -> jdbc.sql("UPDATE refresh_token_families SET expires_at = created_at WHERE id = ?")
                        .param(family).update(),
                CHECK_VIOLATION, "ck_refresh_token_families_expiry");
    }

    // -------------------------------------------------------------------------------------------
    // audit_log is append-only
    // -------------------------------------------------------------------------------------------

    @Test
    void auditLogAcceptsInserts() {
        insertAuditRow();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void auditLogRejectsUpdate() {
        insertAuditRow();  // row triggers only fire if a row is actually affected
        assertAppendOnly(() -> jdbc.sql("UPDATE audit_log SET action = 'tampered'").update(), "UPDATE");
    }

    @Test
    void auditLogRejectsDelete() {
        insertAuditRow();
        assertAppendOnly(() -> jdbc.sql("DELETE FROM audit_log").update(), "DELETE");
    }

    @Test
    void auditLogRejectsTruncate() {
        insertAuditRow();
        assertAppendOnly(() -> jdbc.sql("TRUNCATE audit_log").update(), "TRUNCATE");
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /** The minimal chain of rows needed before an order item can exist. */
    private record Fixture(UUID userId, UUID eventId, UUID seatId, UUID tierId) {}

    private Fixture fixture() {
        UUID userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')")
                .params(userId, userId + "@test.local").update();

        UUID venueId = UUID.randomUUID();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Test Arena', '{}'::jsonb)")
                .param(venueId).update();

        UUID sectionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(sectionId, venueId).update();

        UUID seatId = UUID.randomUUID();
        jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'R1', 1, 0, 0)")
                .params(seatId, sectionId).update();

        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Test Show', now() + interval '7 days', now() + interval '1 day',
                        'SCHEDULED', 4, 'PESSIMISTIC')
                """).params(eventId, venueId).update();

        UUID tierId = UUID.randomUUID();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 250000)")
                .params(tierId, eventId).update();

        jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                .params(eventId, seatId, tierId).update();

        return new Fixture(userId, eventId, seatId, tierId);
    }

    private UUID insertFamily(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO refresh_token_families (id, user_id, expires_at) VALUES (?, ?, now() + interval '30 days')")
                .params(id, userId).update();
        return id;
    }

    private UUID insertRefreshToken(UUID familyId, byte[] hash) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO refresh_tokens (id, family_id, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '7 days')")
                .params(id, familyId, hash).update();
        return id;
    }

    /** An order from its own new hold (V3: every order has a hold, and a hold at most one order). */
    private UUID insertOrder(Fixture f, String status) {
        return insertOrderFor(f, insertHold(f.userId, f.eventId, f.seatId), status);
    }

    private UUID insertOrderFor(Fixture f, UUID holdId, String status) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, ?, 250000, 'ORDER_CREATED')
                """).params(orderId, f.userId, f.eventId, holdId, status).update();
        return orderId;
    }

    /** A CONVERTED hold row; the seat is left alone (only the orders constraints are under test). */
    private UUID insertHold(UUID userId, UUID eventId, UUID seatId) {
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'CONVERTED')
                """).params(holdId, eventId, userId, new UUID[] {seatId}).update();
        return holdId;
    }

    private void insertOrderItem(UUID orderId, Fixture f, boolean active) {
        jdbc.sql("""
                INSERT INTO order_items (order_id, event_id, seat_id, price_paise, active)
                VALUES (?, ?, ?, 250000, ?)
                """).params(orderId, f.eventId, f.seatId, active).update();
    }

    private void insertAuditRow() {
        jdbc.sql("INSERT INTO audit_log (actor, action, entity) VALUES ('test', 'created', 'event')").update();
    }

    /** Asserts the statement fails with the given SQLState, raised by the named constraint. */
    private static void assertViolates(ThrowingCallable statement, String sqlState, String constraint) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    SQLException sql = sqlCause(e);
                    assertThat(sql.getSQLState()).isEqualTo(sqlState);
                    assertThat(sql.getMessage()).contains("\"" + constraint + "\"");
                });
    }

    private static void assertAppendOnly(ThrowingCallable statement, String operation) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    SQLException sql = sqlCause(e);
                    assertThat(sql.getSQLState()).isEqualTo(RAISE_EXCEPTION);
                    assertThat(sql.getMessage()).contains("audit_log is append-only: " + operation);
                });
    }

    private static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("no SQLException in cause chain of " + t, t);
    }
}
