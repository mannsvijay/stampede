package com.stampede.inventory.holds;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.holds.HoldExpirySweeper.SweepResult;
import com.stampede.inventory.strategy.HoldOutcome;
import com.stampede.inventory.strategy.HoldRequest;
import com.stampede.inventory.strategy.InventoryStrategies;
import com.stampede.inventory.strategy.InventoryStrategy;
import com.stampede.testsupport.ExpectInvariantViolations;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The expiry sweeper (M1.4, D-016) against the real pessimistic strategy. Holds are created through
 * the strategy (so the allowance is counted), then made "expired" by moving their times back.
 */
// Pages of 2, so every test with more than two expired holds also goes through the keyset paging.
@JdbcTest(properties = "stampede.holds.sweeper.batch-size=2")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({HoldExpirySweeper.class, HoldExpirySweeperTest.Strategies.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HoldExpirySweeperTest {

    /** The strategy beans are package-private in inventory.strategy, so scan that package. */
    @TestConfiguration
    @ComponentScan("com.stampede.inventory.strategy")
    static class Strategies {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ExecutorService THREADS = Executors.newFixedThreadPool(2);

    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000c3");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000000c4");
    static final List<UUID> USERS = List.of(
            UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
            UUID.fromString("00000000-0000-4000-8000-0000000000d2"),
            UUID.fromString("00000000-0000-4000-8000-0000000000d3"),
            UUID.fromString("00000000-0000-4000-8000-0000000000d4"));
    static final List<UUID> SEATS = List.of(
            UUID.fromString("10000000-0000-4000-8000-0000000000e1"),
            UUID.fromString("90000000-0000-4000-8000-0000000000e2"),
            UUID.fromString("30000000-0000-4000-8000-0000000000e3"),
            UUID.fromString("b0000000-0000-4000-8000-0000000000e4"),
            UUID.fromString("50000000-0000-4000-8000-0000000000e5"),
            UUID.fromString("d0000000-0000-4000-8000-0000000000e6"),
            UUID.fromString("70000000-0000-4000-8000-0000000000e7"),
            UUID.fromString("f0000000-0000-4000-8000-0000000000e8"));

    @Autowired
    HoldExpirySweeper sweeper;

    @Autowired
    InventoryStrategies strategies;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @AfterAll
    static void stopThreads() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void insertFixture() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        for (int i = 0; i < USERS.size(); i++) {
            jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')")
                    .params(USERS.get(i), "sweep" + i + "@example.test").update();
        }
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Sweep venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Sweep event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        int number = 1;
        for (UUID seat : SEATS) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(EVENT, seat, TIER).update();
        }
    }

    @Test
    void expiresOnlyHoldsWhoseTimeRanOutAndGivesTheirSeatsBack() {
        UUID expired = hold(USERS.get(0), SEATS.get(0), SEATS.get(1));
        UUID live = hold(USERS.get(0), SEATS.get(2));
        UUID otherUsersExpired = hold(USERS.get(1), SEATS.get(3));
        backdate(expired);
        backdate(otherUsersExpired);

        assertThat(sweeper.sweepOnce()).isEqualTo(new SweepResult(2, 0, 0));

        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(status(otherUsersExpired)).isEqualTo("EXPIRED");
        assertThat(status(live)).isEqualTo("ACTIVE");
        assertThat(seatStatus(SEATS.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(SEATS.get(1))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(SEATS.get(2))).isEqualTo("HELD");
        assertThat(used(USERS.get(0))).as("only the live hold still counts").isEqualTo(1);
        assertThat(used(USERS.get(1))).isEqualTo(0);

        assertThat(sweeper.sweepOnce()).as("nothing left to do").isEqualTo(new SweepResult(0, 0, 0));
    }

    @Test
    void anExtendedHoldIsNotPicked() {
        UUID holdId = hold(USERS.get(0), SEATS.get(0));
        backdate(holdId);
        jdbc.sql("UPDATE holds SET expires_at = now() + interval '60 seconds', extended = true WHERE id = ?")
                .param(holdId).update();

        assertThat(sweeper.sweepOnce()).isEqualTo(new SweepResult(0, 0, 0));
        assertThat(status(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void aHoldExtendedAfterBeingPickedSurvives() throws Exception {
        // The sweeper reads its candidates without locks. Here the buyer's extension holds the hold row
        // lock while the sweeper reads, so the sweeper picks the (still expired) hold and then waits in
        // release(EXPIRED). The extension commits; release re-checks under the lock: NotExpired.
        UUID holdId = hold(USERS.get(0), SEATS.get(0), SEATS.get(1));
        backdate(holdId);
        try (Connection buyer = dataSource.getConnection()) {
            buyer.setAutoCommit(false);
            exec(buyer, "SELECT 1 FROM holds WHERE id = ? FOR UPDATE", holdId);

            Future<SweepResult> sweep = THREADS.submit(sweeper::sweepOnce);
            waitUntilOneSessionIsBlockedOnALock();

            exec(buyer, "UPDATE holds SET expires_at = now() + interval '60 seconds', extended = true WHERE id = ?",
                    holdId);
            buyer.commit();

            assertThat(sweep.get(30, TimeUnit.SECONDS)).isEqualTo(new SweepResult(0, 1, 0));
        }
        assertThat(status(holdId)).isEqualTo("ACTIVE");
        assertThat(seatStatus(SEATS.get(0))).isEqualTo("HELD");
        assertThat(used(USERS.get(0))).isEqualTo(2);
    }

    @Test
    @ExpectInvariantViolations(rules = {"I8"},
            reason = "Deliberate drift: the allowance row is deleted so the sweeper's release must refuse this hold")
    void oneBrokenHoldDoesNotStopTheOthers() {
        UUID broken = hold(USERS.get(0), SEATS.get(0));
        UUID fine = hold(USERS.get(1), SEATS.get(1));
        backdate(broken);
        backdate(fine);
        // Drift on purpose: the allowance row is gone, so release refuses this hold and rolls back.
        jdbc.sql("DELETE FROM user_event_allowance WHERE user_id = ?").param(USERS.get(0)).update();

        assertThat(sweeper.sweepOnce()).isEqualTo(new SweepResult(1, 0, 1));

        assertThat(status(broken)).isEqualTo("ACTIVE");
        assertThat(seatStatus(SEATS.get(0))).isEqualTo("HELD");
        assertThat(status(fine)).isEqualTo("EXPIRED");
        assertThat(seatStatus(SEATS.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @ExpectInvariantViolations(rules = {"I8"},
            reason = "Deliberate drift: the allowance row is deleted so the sweeper's release must refuse these holds")
    void brokenHoldsDoNotStarveHealthyOnesBehindThem() {
        // Three holds that can't be released are the OLDEST, so they fill the first page (size 2)
        // and half the second on every sweep. Keyset paging must still reach the healthy ones.
        List<UUID> broken = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID holdId = hold(USERS.get(0), SEATS.get(i));
            backdate(holdId, "10 minutes");
            broken.add(holdId);
        }
        jdbc.sql("DELETE FROM user_event_allowance WHERE user_id = ?").param(USERS.get(0)).update();
        UUID healthyA = hold(USERS.get(1), SEATS.get(3));
        UUID healthyB = hold(USERS.get(2), SEATS.get(4));
        backdate(healthyA);
        backdate(healthyB);

        assertThat(sweeper.sweepOnce()).isEqualTo(new SweepResult(2, 0, 3));

        assertThat(status(healthyA)).isEqualTo("EXPIRED");
        assertThat(status(healthyB)).isEqualTo("EXPIRED");
        broken.forEach(holdId -> assertThat(status(holdId)).isEqualTo("ACTIVE"));
        assertThat(sweeper.sweepOnce()).as("broken holds are retried, and still refused")
                .isEqualTo(new SweepResult(0, 0, 3));
    }

    @Test
    void twoSweepersAtOnceExpireEveryHoldExactlyOnce() throws Exception {
        // D-016 option (a): every instance sweeps. Each hold must be expired once and its seats given
        // back once; the other sweeper sees NotActive (skipped), never an error or a double decrement.
        // The overlap is forced, not left to timing: another session locks the OLDEST hold's row, so
        // both sweepers read their candidates and then both wait on that same row. Only once both
        // are waiting is the lock let go; one of them must then get NotActive for that hold.
        List<UUID> holds = new ArrayList<>();
        for (int i = 0; i < SEATS.size(); i++) {
            UUID holdId = hold(USERS.get(i % USERS.size()), SEATS.get(i));
            backdate(holdId, i == 0 ? "10 minutes" : "1 minute");
            holds.add(holdId);
        }

        SweepResult a;
        SweepResult b;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            exec(blocker, "SELECT 1 FROM holds WHERE id = ? FOR UPDATE", holds.get(0));

            Future<SweepResult> first = THREADS.submit(sweeper::sweepOnce);
            Future<SweepResult> second = THREADS.submit(sweeper::sweepOnce);
            waitUntilSessionsAreBlockedOnALock(2);
            blocker.rollback();

            a = first.get(30, TimeUnit.SECONDS);
            b = second.get(30, TimeUnit.SECONDS);
        }

        assertThat(a.expired() + b.expired()).isEqualTo(holds.size());
        assertThat(a.skipped() + b.skipped()).as("the overlap happened").isPositive();
        assertThat(a.failed() + b.failed()).isZero();
        for (UUID holdId : holds) {
            assertThat(status(holdId)).isEqualTo("EXPIRED");
        }
        for (UUID user : USERS) {
            assertThat(used(user)).as("allowance of %s", user).isZero();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM event_seats WHERE status <> 'AVAILABLE'").query(Long.class).single())
                .isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private UUID hold(UUID user, UUID... seats) {
        InventoryStrategy strategy = strategies.forKind("PESSIMISTIC");
        UUID holdId = UUID.randomUUID();
        HoldOutcome outcome = strategy.hold(new HoldRequest(holdId, EVENT, user, List.of(seats), Duration.ofMinutes(5)));
        assertThat(outcome).isInstanceOf(HoldOutcome.Held.class);
        return holdId;
    }

    /** The hold expired a minute ago. */
    private void backdate(UUID holdId) {
        backdate(holdId, "1 minute");
    }

    /** The hold expired {@code ago} ago (a Postgres interval) and was created 5 minutes before that. */
    private void backdate(UUID holdId, String ago) {
        jdbc.sql("""
                UPDATE holds SET created_at = now() - CAST(? AS interval) - interval '5 minutes',
                                 expires_at = now() - CAST(? AS interval)
                WHERE id = ?
                """).params(ago, ago, holdId).update();
    }

    private String status(UUID holdId) {
        return jdbc.sql("SELECT status FROM holds WHERE id = ?").param(holdId).query(String.class).single();
    }

    private String seatStatus(UUID seatId) {
        return jdbc.sql("SELECT status FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, seatId).query(String.class).single();
    }

    private int used(UUID user) {
        return jdbc.sql("SELECT used FROM user_event_allowance WHERE event_id = ? AND user_id = ?")
                .params(EVENT, user).query(Integer.class).single();
    }

    private static void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    private void waitUntilOneSessionIsBlockedOnALock() throws InterruptedException {
        waitUntilSessionsAreBlockedOnALock(1);
    }

    /** Polls Postgres until exactly {@code sessions} sessions are waiting for a row lock (at most 10 s). */
    private void waitUntilSessionsAreBlockedOnALock(int sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting == sessions) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected exactly " + sessions + " sessions waiting for a row lock within 10 s");
    }
}
