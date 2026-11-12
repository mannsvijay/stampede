package com.stampede.control.invariants;

import com.stampede.testsupport.TestDatabases;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves every rule of the invariant checker can fail (M1.7, D-019). Each test starts from a valid
 * baseline that covers every state the rules look at, breaks it in one way, and asserts exactly which
 * rules fire. A rule that never fires here could be a query that never fires at all.
 * <p>
 * Baseline, one event at a 4-seat venue:
 * <ul>
 *   <li>S1, S4 AVAILABLE;</li>
 *   <li>S2 HELD by alice's ACTIVE hold H1 (alice used = 1);</li>
 *   <li>S3 SOLD to bob's PAID order O1 at TICKETS_ISSUED: CONVERTED hold H2, one active item, one
 *       SUCCEEDED payment of the total, one live ticket held by bob (bob used = 1).</li>
 * </ul>
 * Own database (on the shared test Postgres, TestDatabases): some tests break the schema's
 * guarantees (O6) and none of this may leak into another test class's database.
 * <p>
 * Each test runs in {@code @JdbcTest}'s test transaction, which is rolled back: the checker joins
 * it and sees the broken state, and nothing broken is ever committed, so the after-test assertion
 * ({@code InvariantAssertionListener}) finds this database clean without any opt-out. The two tests
 * marked NOT_SUPPORTED commit only valid data (O6 rolls its own transaction back).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InvariantChecker.class)
class InvariantCheckerTest {

    /** Its own database on the shared test Postgres (TestDatabases): nothing else ever sees its data. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "invariant_checker");
    }

    static final Duration STRICT = Duration.ZERO;
    static final Duration GRACE = Duration.ofSeconds(30);
    static final long PRICE = 150_000;

    static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b0");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID OTHER_VENUE = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final UUID OTHER_SECTION = UUID.fromString("00000000-0000-4000-8000-000000000004");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000000e");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final UUID S1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID S2 = UUID.fromString("20000000-0000-4000-8000-000000000002");
    static final UUID S3 = UUID.fromString("30000000-0000-4000-8000-000000000003");
    static final UUID S4 = UUID.fromString("40000000-0000-4000-8000-000000000004");
    /** A seat of the other venue. */
    static final UUID FOREIGN_SEAT = UUID.fromString("50000000-0000-4000-8000-000000000005");
    static final UUID H1 = UUID.fromString("00000000-0000-4000-8000-0000000001a1");
    static final UUID H2 = UUID.fromString("00000000-0000-4000-8000-0000000001a2");
    static final UUID O1 = UUID.fromString("00000000-0000-4000-8000-0000000002b1");

    @Autowired
    InvariantChecker checker;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void insertBaseline() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'alice@example.test', 'x')").param(ALICE).update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'bob@example.test', 'x')").param(BOB).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Venue', '{}'), (?, 'Other venue', '{}')")
                .params(VENUE, OTHER_VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD'), (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE, OTHER_SECTION, OTHER_VENUE).update();
        int number = 1;
        for (UUID seat : List.of(S1, S2, S3, S4)) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
        }
        jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', 1, 0, 0)")
                .params(FOREIGN_SEAT, OTHER_SECTION).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Event', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', ?)")
                .params(TIER, EVENT, PRICE).update();
        for (UUID seat : List.of(S1, S2, S3, S4)) {
            addEventSeat(seat);
        }

        hold(H1, ALICE, "ACTIVE", S2);
        held(S2, H1);
        allowance(ALICE, 1);

        hold(H2, BOB, "CONVERTED", S3);
        order(O1, BOB, H2, "PAID", "TICKETS_ISSUED", PRICE);
        item(O1, S3, true);
        payment(O1, "SUCCEEDED", PRICE);
        sold(S3, O1);
        ticket(O1, S3, BOB);
        allowance(BOB, 1);
    }

    @Test
    void theBaselineBreaksNoRule() {
        assertThat(checker.check(EVENT, STRICT)).isEmpty();
        assertThat(checker.check(EVENT, GRACE)).isEmpty();
    }

    @Test
    void everyRuleHasATestHere() {
        // Adding a rule without a test that makes it fire must fail here first.
        assertThat(checker.ruleIds()).containsExactly(
                "I1", "I2", "I3", "I8", "V1", "O1", "O2", "O3", "O4", "O5", "O6", "O7", "C1", "T1", "T2", "T3");
    }

    // --- holds -----------------------------------------------------------------------------------

    @Test
    void i1SeatListedByTwoActiveHolds() {
        // A second active hold lists S2, which H1 owns: that is also I3 (the seat isn't held by it).
        hold(UUID.randomUUID(), ALICE, "ACTIVE", S2);
        allowance(ALICE, 2);
        assertRules(STRICT, "I1", "I3");
    }

    @Test
    void i2HeldSeatWhoseHoldIsNoLongerActive() {
        jdbc.sql("UPDATE holds SET status = 'RELEASED' WHERE id = ?").param(H1).update();
        allowance(ALICE, 0);
        assertRules(STRICT, "I2");
    }

    @Test
    void i3ActiveHoldWhoseSeatWasFreedUnderIt() {
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, S2).update();
        assertRules(STRICT, "I3");
    }

    @Test
    void i8AllowanceThatDriftedFromTheHolds() {
        allowance(ALICE, 2);
        assertRules(STRICT, "I8");
    }

    @Test
    void i8UserWithHoldsButNoAllowanceRow() {
        jdbc.sql("DELETE FROM user_event_allowance WHERE user_id = ?").param(ALICE).update();
        assertRules(STRICT, "I8");
    }

    @Test
    void i8UsedAboveTheLimitEvenWhenTheCounterMatches() {
        // Exactly what an admin lowering the limit below existing holds would produce. It stays a
        // violation (D-019): a limit change must refuse to go below any user's current used.
        UUID second = UUID.randomUUID();
        hold(second, ALICE, "ACTIVE", S4);
        held(S4, second);
        allowance(ALICE, 2);
        jdbc.sql("UPDATE events SET per_user_limit = 1 WHERE id = ?").param(EVENT).update();
        assertRules(STRICT, "I8");
    }

    // --- venue and capacity ----------------------------------------------------------------------

    @Test
    void v1EventSeatFromAnotherVenue() {
        addEventSeat(FOREIGN_SEAT);
        assertRules(STRICT, "V1");
    }

    @Test
    void c1MoreSoldSeatsThanTheVenueHas() {
        // PRD 6.10 #2. The venue loses all four seats to the other venue, so its capacity is 0 while
        // S3 stays SOLD. C1 can't fire without another rule breaking first (here V1, for every seat).
        jdbc.sql("UPDATE seats SET row_label = 'B', section_id = ? WHERE section_id = ?").params(OTHER_SECTION, SECTION).update();
        assertRules(STRICT, "V1", "C1");
    }

    // --- orders ----------------------------------------------------------------------------------

    @Test
    void o1SoldSeatWithoutAnItemInItsOrder() {
        // PRD 6.10 #4: S4 is SOLD to O1, but O1 has no item for it.
        sold(S4, O1);
        assertRules(STRICT, "O1");
    }

    @Test
    void o2PaidOrderWhoseSeatIsNotSoldToIt() {
        // The live ticket for S3 now points at an unsold seat as well (T1).
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', order_id = NULL WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, S3).update();
        assertRules(STRICT, "O2", "T1");
    }

    @Test
    void o3CancelledOrderThatKeptAnActiveItem() {
        UUID released = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        hold(released, ALICE, "RELEASED", S1);
        order(cancelled, ALICE, released, "CANCELLED", "ORDER_CANCELLED", PRICE);
        item(cancelled, S1, true);
        assertRules(STRICT, "O3");
    }

    @Test
    void o4PaymentThatDoesNotMatchTheTotal() {
        jdbc.sql("UPDATE payments SET amount_paise = amount_paise - 1 WHERE order_id = ?").param(O1).update();
        assertRules(STRICT, "O4");
    }

    @Test
    void o4CancelledOrderStillChargedIsExcusedOnlyWhileTheRefundCanStillCome() {
        // Compensation cancels, then refunds (D-017): in between, CANCELLED with a SUCCEEDED charge.
        UUID released = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        hold(released, ALICE, "RELEASED", S1);
        order(cancelled, ALICE, released, "CANCELLED", "ORDER_CANCELLED", PRICE);
        item(cancelled, S1, false);
        payment(cancelled, "SUCCEEDED", PRICE);

        assertRules(STRICT, "O4");
        assertRules(GRACE);
        backdate(cancelled);
        assertRules(GRACE, "O4");
    }

    @Test
    void o5PaidOrderWhoseHoldIsNotConverted() {
        jdbc.sql("UPDATE holds SET status = 'RELEASED' WHERE id = ?").param(H2).update();
        allowance(BOB, 0);
        assertRules(STRICT, "O5");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)  // the baseline is committed; only the drop is rolled back
    void o6SeatInTwoActiveOrdersWithTheUniqueIndexBypassed() {
        // PRD 6.10 #1. uq_order_items_active_seat makes this state impossible, so the only way to
        // show the rule fires is to remove the index. The ONE exception to CLAUDE.md's "never drop or
        // relax it" (D-019): only here, in this class's own throwaway database, inside a transaction
        // that is always rolled back. Postgres DDL is transactional, so the rollback restores it.
        TransactionTemplate rolledBack = new TransactionTemplate(transactions);
        List<Violation> violations = rolledBack.execute(status -> {
            status.setRollbackOnly();
            jdbc.sql("DROP INDEX uq_order_items_active_seat").update();
            UUID released = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            hold(released, ALICE, "RELEASED", S3);
            order(second, ALICE, released, "PENDING", "ORDER_CREATED", PRICE);
            item(second, S3, true);
            // Joins this transaction, so it sees the duplicate.
            return checker.check(EVENT, STRICT);
        });

        // S3 also has two active items (O1), and the second order is PENDING (O7, strict).
        assertThat(ruleIds(violations)).as(violations.toString()).containsExactlyInAnyOrder("O1", "O6", "O7");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_order_items_active_seat'")
                .query(Long.class).single()).as("index restored by the rollback").isEqualTo(1);
        assertRules(STRICT);
    }

    @Test
    void o7PendingOrderIsExcusedOnlyWhileACheckoutCouldStillBeRunning() {
        // Between steps 1 and 3 (D-017): hold ACTIVE, seat HELD, order PENDING with an active item.
        UUID holdId = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        hold(holdId, ALICE, "ACTIVE", S1);
        held(S1, holdId);
        allowance(ALICE, 2);
        order(pending, ALICE, holdId, "PENDING", "ORDER_CREATED", PRICE);
        item(pending, S1, true);

        assertRules(STRICT, "O7");
        assertRules(GRACE);
        backdate(pending);
        assertRules(GRACE, "O7");
    }

    @Test
    void soldSeatsOfAPendingOrderAreExcusedOnlyBetweenSteps3And4() {
        // Step 3 committed (seat SOLD, hold CONVERTED), step 4 (PAID) not yet. Past the grace period
        // this is D-017's "step 4 fails every attempt": O1, O5 and O7 report it.
        UUID holdId = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        hold(holdId, ALICE, "CONVERTED", S1);
        allowance(ALICE, 2);
        order(pending, ALICE, holdId, "PENDING", "SEATS_SOLD", PRICE);
        item(pending, S1, true);
        payment(pending, "SUCCEEDED", PRICE);
        sold(S1, pending);

        assertRules(STRICT, "O1", "O5", "O7");
        assertRules(GRACE);
        backdate(pending);
        assertRules(GRACE, "O1", "O5", "O7");
    }

    // --- tickets ---------------------------------------------------------------------------------

    @Test
    void t1LiveTicketHeldBySomeoneOtherThanTheBuyer() {
        jdbc.sql("UPDATE tickets SET holder_user_id = ? WHERE order_id = ?").params(ALICE, O1).update();
        assertRules(STRICT, "T1");
    }

    @Test
    void t2OrderAtTicketsIssuedWithATicketMissing() {
        jdbc.sql("DELETE FROM tickets WHERE order_id = ?").param(O1).update();
        assertRules(STRICT, "T2");
    }

    @Test
    void t3PaidOrderWithoutTicketsIsExcusedOnlyWhileStep5CouldStillBeRunning() {
        // PRD 6.10 #3. Past the grace period this is D-018's "PAID without tickets".
        jdbc.sql("DELETE FROM tickets WHERE order_id = ?").param(O1).update();
        jdbc.sql("UPDATE orders SET saga_state = 'SEATS_SOLD' WHERE id = ?").param(O1).update();

        assertRules(STRICT, "T3");
        assertRules(GRACE);
        backdate(O1);
        assertRules(GRACE, "T3");
    }

    // --- scope -----------------------------------------------------------------------------------

    @Test
    void checksAreScopedToTheirEvent() {
        UUID other = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Other', now() + interval '30 days', now() - interval '1 hour', 'DRAFT', 4, 'PESSIMISTIC')
                """).params(other, VENUE).update();
        allowance(ALICE, 2);  // breaks I8 for EVENT only

        assertThat(checker.check(other, STRICT)).isEmpty();
        assertThat(checker.checkAllEvents(STRICT)).extracting(Violation::eventId).containsOnly(EVENT);
        assertThat(checker.checkOpenEvents(STRICT)).extracting(Violation::eventId).containsOnly(EVENT);
        jdbc.sql("UPDATE events SET status = 'ENDED' WHERE id = ?").param(EVENT).update();
        assertThat(checker.checkOpenEvents(STRICT)).as("ENDED is not checked by the live run").isEmpty();
    }

    @Test
    void aViolationReadsAsRuleThenDetail() {
        allowance(ALICE, 2);
        assertThat(checker.check(EVENT, STRICT)).singleElement().asString()
                .isEqualTo("I8 allowance drift: user " + ALICE + " has used = 2 but 1 seats in active or converted holds"
                        + " (limit 4)");
    }

    // --- speed (decision 3) ----------------------------------------------------------------------

    /**
     * The demo event's size, fully sold: 2,000 seats, 500 buyers with 4 seats each, every order PAID
     * with its tickets. Prints each rule's time; the bound is loose so CI noise can't fail it.
     */
    @Nested
    class OnASoldOut2000SeatEvent {

        static final UUID PERF_VENUE = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
        static final UUID PERF_SECTION = UUID.fromString("00000000-0000-4000-8000-0000000000f2");
        static final UUID PERF_EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000f3");
        static final UUID PERF_TIER = UUID.fromString("00000000-0000-4000-8000-0000000000f4");

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)  // realistic: committed rows, planner stats
        void aFullCheckTakesWellUnderTwoSeconds() {
            insertSoldOutEvent();
            assertThat(checker.check(PERF_EVENT, STRICT)).as("the fixture itself is valid").isEmpty();

            Map<String, Long> microsByRule = new LinkedHashMap<>();
            List<Long> fullChecks = new ArrayList<>();
            for (int run = 0; run < 6; run++) {
                long start = System.nanoTime();
                checker.check(PERF_EVENT, STRICT);
                if (run > 0) {  // run 0 warms up
                    fullChecks.add((System.nanoTime() - start) / 1_000);
                }
            }
            for (InvariantCheck rule : SqlRules.ALL) {
                long start = System.nanoTime();
                rule.findViolations(jdbc, PERF_EVENT, STRICT);
                microsByRule.put(rule.id(), (System.nanoTime() - start) / 1_000);
            }
            long median = fullChecks.stream().sorted().toList().get(fullChecks.size() / 2);
            System.out.println("[invariant timing] full check, 2000 sold seats: median " + median / 1000.0
                    + " ms of " + fullChecks + " us; per rule (us): " + microsByRule);

            assertThat(median).as("median full check in microseconds").isLessThan(2_000_000);
        }

        private void insertSoldOutEvent() {
            jdbc.sql("""
                    INSERT INTO users (id, email, password_hash)
                    SELECT md5('user' || k)::uuid, 'perf' || k || '@example.test', 'x' FROM generate_series(1, 500) k
                    """).update();
            jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Perf venue', '{}')").param(PERF_VENUE).update();
            jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                    .params(PERF_SECTION, PERF_VENUE).update();
            jdbc.sql("""
                    INSERT INTO seats (id, section_id, row_label, number, x, y)
                    SELECT md5('seat' || g)::uuid, :section, 'A', g, 0, 0 FROM generate_series(1, 2000) g
                    """).param("section", PERF_SECTION).update();
            jdbc.sql("""
                    INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                    VALUES (?, ?, 'Perf', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4, 'PESSIMISTIC')
                    """).params(PERF_EVENT, PERF_VENUE).update();
            jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', ?)")
                    .params(PERF_TIER, PERF_EVENT, PRICE).update();
            // Buyer k has seats 4k-3 .. 4k, one CONVERTED hold and one PAID order for them.
            jdbc.sql("""
                    INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                    SELECT md5('hold' || k)::uuid, :event, md5('user' || k)::uuid,
                           ARRAY(SELECT md5('seat' || g)::uuid FROM generate_series(4 * k - 3, 4 * k) g),
                           now() + interval '5 minutes', 'CONVERTED'
                    FROM generate_series(1, 500) k
                    """).param("event", PERF_EVENT).update();
            jdbc.sql("""
                    INSERT INTO orders (id, user_id, event_id, status, total_paise, saga_state, hold_id)
                    SELECT md5('order' || k)::uuid, md5('user' || k)::uuid, :event, 'PAID', 4 * :price,
                           'TICKETS_ISSUED', md5('hold' || k)::uuid
                    FROM generate_series(1, 500) k
                    """).param("event", PERF_EVENT).param("price", PRICE).update();
            jdbc.sql("""
                    INSERT INTO event_seats (event_id, seat_id, tier_id, status, order_id, version)
                    SELECT :event, md5('seat' || g)::uuid, :tier, 'SOLD', md5('order' || ((g - 1) / 4 + 1))::uuid, 2
                    FROM generate_series(1, 2000) g
                    """).param("event", PERF_EVENT).param("tier", PERF_TIER).update();
            jdbc.sql("""
                    INSERT INTO order_items (order_id, event_id, seat_id, price_paise)
                    SELECT md5('order' || ((g - 1) / 4 + 1))::uuid, :event, md5('seat' || g)::uuid, :price
                    FROM generate_series(1, 2000) g
                    """).param("event", PERF_EVENT).param("price", PRICE).update();
            jdbc.sql("""
                    INSERT INTO payments (order_id, status, amount_paise)
                    SELECT md5('order' || k)::uuid, 'SUCCEEDED', 4 * :price FROM generate_series(1, 500) k
                    """).param("price", PRICE).update();
            jdbc.sql("""
                    INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                         section_code, row_label, seat_number)
                    SELECT md5('order' || ((g - 1) / 4 + 1))::uuid, :event, md5('seat' || g)::uuid,
                           md5('user' || ((g - 1) / 4 + 1))::uuid, decode(repeat('00', 64), 'hex'), 'ISSUED',
                           '0123abcd', 'A', 'A', g
                    FROM generate_series(1, 2000) g
                    """).param("event", PERF_EVENT).update();
            jdbc.sql("""
                    INSERT INTO user_event_allowance (event_id, user_id, used)
                    SELECT :event, md5('user' || k)::uuid, 4 FROM generate_series(1, 500) k
                    """).param("event", PERF_EVENT).update();
            jdbc.sql("ANALYZE").update();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void assertRules(Duration grace, String... expectedRuleIds) {
        List<Violation> violations = checker.check(EVENT, grace);
        assertThat(ruleIds(violations)).as("grace %s: %s", grace, violations).containsExactlyInAnyOrder(expectedRuleIds);
    }

    private static List<String> ruleIds(List<Violation> violations) {
        return violations.stream().map(Violation::ruleId).distinct().toList();
    }

    private void addEventSeat(UUID seat) {
        jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                .params(EVENT, seat, TIER).update();
    }

    private void hold(UUID id, UUID user, String status, UUID... seats) {
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', ?)
                """).params(id, EVENT, user, seats, status).update();
    }

    private void held(UUID seat, UUID holdId) {
        jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, order_id = NULL WHERE event_id = ? AND seat_id = ?")
                .params(holdId, EVENT, seat).update();
    }

    private void sold(UUID seat, UUID orderId) {
        jdbc.sql("UPDATE event_seats SET status = 'SOLD', hold_id = NULL, order_id = ? WHERE event_id = ? AND seat_id = ?")
                .params(orderId, EVENT, seat).update();
    }

    private void allowance(UUID user, int used) {
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, ?)
                ON CONFLICT (event_id, user_id) DO UPDATE SET used = EXCLUDED.used
                """).params(EVENT, user, used).update();
    }

    private void order(UUID id, UUID user, UUID holdId, String status, String sagaState, long total) {
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, status, total_paise, saga_state, hold_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(id, user, EVENT, status, total, sagaState, holdId).update();
    }

    private void item(UUID orderId, UUID seat, boolean active) {
        jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise, active) VALUES (?, ?, ?, ?, ?)")
                .params(orderId, EVENT, seat, PRICE, active).update();
    }

    private void payment(UUID orderId, String status, long amount) {
        jdbc.sql("INSERT INTO payments (order_id, status, amount_paise) VALUES (?, ?, ?)")
                .params(orderId, status, amount).update();
    }

    private void ticket(UUID orderId, UUID seat, UUID holder) {
        jdbc.sql("""
                INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                     section_code, row_label, seat_number)
                VALUES (?, ?, ?, ?, decode(repeat('00', 64), 'hex'), 'ISSUED', '0123abcd', 'A', 'A', 1)
                """).params(orderId, EVENT, seat, holder).update();
    }

    /** Makes an order older than {@link #GRACE}: its checkout can't still be running. */
    private void backdate(UUID orderId) {
        jdbc.sql("UPDATE orders SET created_at = now() - interval '1 hour' WHERE id = ?").param(orderId).update();
    }
}
