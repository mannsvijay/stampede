package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Races many threads against any {@link InventoryStrategy} for many rounds and checks invariants after
 * every round (M1.3). It only knows the interface, so M2.3 runs it unchanged over all three strategies.
 * <p>
 * One round: reset the event's seats and holds; build the round's plan from its seed (which thread
 * does what, with which seats, in which order); run the setup calls one by one; release all threads
 * through one start gate; record every outcome and exception; check the invariants.
 * <p>
 * A round's seed replays its <em>inputs</em>, not its thread interleaving (the OS scheduler decides
 * that), so a replayed failure may not fail again; the seed still shows the shape of the input.
 */
final class ConcurrencyHarness {

    /** The event and seats every round uses. */
    record Fixture(UUID eventId, UUID userId, List<UUID> seatIds) {
    }

    /**
     * @param rounds   {@code -Dstampede.harness.rounds}, default 100 (as in CI); the full proof
     *                 (the M1 exit criterion) passes 1000
     * @param baseSeed {@code -Dstampede.harness.seed}; round r uses seed {@code baseSeed + r}, so
     *                 {@code -Dstampede.harness.seed=<round seed> -Dstampede.harness.rounds=1} replays it
     */
    record Config(int threads, int rounds, long baseSeed) {
        static Config fromSystemProperties(int threads) {
            int rounds = Integer.getInteger("stampede.harness.rounds", 100);
            String seed = System.getProperty("stampede.harness.seed");
            long baseSeed = seed != null ? Long.parseLong(seed) : new SecureRandom().nextLong();
            return new Config(threads, rounds, baseSeed);
        }
    }

    /** One call a thread makes. */
    sealed interface Action {
        record Hold(UUID holdId, List<UUID> seatIds) implements Action {
        }

        record Release(UUID holdId, ReleaseReason reason) implements Action {
        }
    }

    /**
     * @param setup   holds made one by one before the race (they must succeed)
     * @param threads one list of actions per thread, run in order by that thread
     */
    record RoundPlan(List<Action.Hold> setup, List<List<Action>> threads) {
    }

    /** What a scenario provides: its plan per round, and any extra checks on the winners. */
    interface Scenario {
        String name();

        /**
         * The event's per_user_limit for this scenario (every call is by the fixture's one user). By
         * default the number of seats, so the cap never rejects but its row is still locked on every
         * hold and release, which keeps the allowance in the lock-order mix (D-015).
         */
        default int perUserLimit(Fixture fixture) {
            return fixture.seatIds().size();
        }

        RoundPlan plan(Random random, Fixture fixture, int threads);

        /** Extra checks on this round's race results (setup excluded); empty list = fine. */
        default List<String> checkRace(List<CallResult> race) {
            return List.of();
        }
    }

    /** The result of one call: exactly one of outcome / error is set. */
    record CallResult(Action action, Object outcome, Throwable error, long nanos) {
    }

    private static final long ROUND_TIMEOUT_SECONDS = 30;

    private final InventoryStrategy strategy;
    private final JdbcClient jdbc;
    private final Fixture fixture;
    private final Config config;
    private final ExecutorService threads;

    ConcurrencyHarness(InventoryStrategy strategy, JdbcClient jdbc, Fixture fixture, Config config,
            ExecutorService threads) {
        this.strategy = strategy;
        this.jdbc = jdbc;
        this.fixture = fixture;
        this.config = config;
        this.threads = threads;
    }

    HarnessReport run(Scenario scenario) {
        HarnessReport report = new HarnessReport(strategy.kind(), scenario.name(), config);
        jdbc.sql("UPDATE events SET per_user_limit = ? WHERE id = ?")
                .params(scenario.perUserLimit(fixture), fixture.eventId()).update();
        long start = System.nanoTime();
        for (int round = 0; round < config.rounds(); round++) {
            long seed = config.baseSeed() + round;
            reset();
            RoundPlan plan = scenario.plan(new Random(seed), fixture, config.threads());

            List<CallResult> setup = new ArrayList<>();
            for (Action.Hold hold : plan.setup()) {
                setup.add(call(hold));
            }
            List<CallResult> race;
            try {
                race = runConcurrently(plan.threads());
            } catch (TimeoutException e) {
                // A thread is stuck (e.g. waiting on a lock forever): later rounds can't be trusted.
                report.abort(round, seed, "round did not finish within " + ROUND_TIMEOUT_SECONDS + " s");
                break;
            }

            List<String> violations = new ArrayList<>();
            for (CallResult result : setup) {
                if (!(result.outcome() instanceof Held)) {
                    violations.add("setup hold failed: " + describe(result));
                }
            }
            violations.addAll(check(scenario, setup, race));
            report.record(round, seed, setup, race, violations);
        }
        report.finish(System.nanoTime() - start);
        return report;
    }

    /** Empties the event: seats back to AVAILABLE at version 0, its holds and allowance rows deleted. */
    private void reset() {
        // Seats first: event_seats.hold_id references holds.
        jdbc.sql("""
                UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, order_id = NULL, version = 0
                WHERE event_id = ?
                """).param(fixture.eventId()).update();
        jdbc.sql("DELETE FROM holds WHERE event_id = ?").param(fixture.eventId()).update();
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ?").param(fixture.eventId()).update();
    }

    private List<CallResult> runConcurrently(List<List<Action>> plans) throws TimeoutException {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<List<CallResult>>> futures = new ArrayList<>();
        for (List<Action> actions : plans) {
            futures.add(threads.submit(() -> {
                startGate.await();
                List<CallResult> results = new ArrayList<>();
                for (Action action : actions) {
                    results.add(call(action));
                }
                return results;
            }));
        }
        startGate.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUND_TIMEOUT_SECONDS);
        List<CallResult> all = new ArrayList<>();
        for (Future<List<CallResult>> future : futures) {
            try {
                all.addAll(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("call() never throws; the harness itself failed", e);
            }
        }
        return all;
    }

    /** Calls the strategy once; any exception is recorded, never thrown (I7 counts them). */
    private CallResult call(Action action) {
        long start = System.nanoTime();
        try {
            Object outcome = switch (action) {
                case Action.Hold hold -> strategy.hold(new HoldRequest(hold.holdId(), fixture.eventId(),
                        fixture.userId(), hold.seatIds(), java.time.Duration.ofMinutes(5)));
                case Action.Release release -> strategy.release(fixture.eventId(), release.holdId(), release.reason());
            };
            return new CallResult(action, outcome, null, System.nanoTime() - start);
        } catch (RuntimeException e) {
            return new CallResult(action, null, e, System.nanoTime() - start);
        }
    }

    // --- invariants --------------------------------------------------------------------------------

    private List<String> check(Scenario scenario, List<CallResult> setup, List<CallResult> race) {
        List<CallResult> all = new ArrayList<>(setup);
        all.addAll(race);
        List<String> violations = new ArrayList<>();

        // I7: zero exceptions (deadlocks show up here as SQLState 40P01).
        for (CallResult result : all) {
            if (result.error() != null) {
                violations.add("I7 exception: " + describe(result));
            }
        }

        // Which holds won and are still alive, according to the outcomes.
        Map<UUID, List<UUID>> won = new LinkedHashMap<>();
        Set<UUID> released = new HashSet<>();
        Map<UUID, Long> expectedVersion = new HashMap<>();
        for (CallResult result : all) {
            if (result.outcome() instanceof Held held) {
                won.put(held.holdId(), held.seatIds());
                held.seatIds().forEach(seat -> expectedVersion.merge(seat, 1L, Long::sum));
            } else if (result.outcome() instanceof Released freed) {
                released.add(((Action.Release) result.action()).holdId());
                freed.seatIds().forEach(seat -> expectedVersion.merge(seat, 1L, Long::sum));
            }
        }

        // I4/I5: live winners never share a seat, and the database holds exactly what they won.
        Map<UUID, UUID> expectedOwner = new HashMap<>();
        for (Map.Entry<UUID, List<UUID>> hold : won.entrySet()) {
            if (released.contains(hold.getKey())) {
                continue;
            }
            for (UUID seat : hold.getValue()) {
                UUID previous = expectedOwner.put(seat, hold.getKey());
                if (previous != null) {
                    violations.add("I4 seat " + seat + " won by two live holds " + previous + " and " + hold.getKey());
                }
            }
        }
        Map<UUID, UUID> actualOwner = new HashMap<>();
        jdbc.sql("SELECT seat_id, hold_id FROM event_seats WHERE event_id = ? AND status = 'HELD'")
                .param(fixture.eventId())
                .query(rs -> {
                    actualOwner.put(rs.getObject("seat_id", UUID.class), rs.getObject("hold_id", UUID.class));
                });
        if (!actualOwner.equals(expectedOwner)) {
            violations.add("I5 HELD seats in the database " + actualOwner + " != seats of live winning holds "
                    + expectedOwner);
        }
        Set<UUID> activeHolds = new HashSet<>(jdbc.sql("SELECT id FROM holds WHERE event_id = ? AND status = 'ACTIVE'")
                .param(fixture.eventId()).query(UUID.class).list());
        Set<UUID> liveWinners = new HashSet<>(won.keySet());
        liveWinners.removeAll(released);
        if (!activeHolds.equals(liveWinners)) {
            violations.add("I5 ACTIVE holds in the database " + activeHolds + " != live winning holds " + liveWinners);
        }

        // I6: no lost updates. Every successful transition bumps a seat's version by exactly 1.
        jdbc.sql("SELECT seat_id, version FROM event_seats WHERE event_id = ?")
                .param(fixture.eventId())
                .query(rs -> {
                    UUID seat = rs.getObject("seat_id", UUID.class);
                    long expected = expectedVersion.getOrDefault(seat, 0L);
                    long actual = rs.getLong("version");
                    if (actual != expected) {
                        violations.add("I6 seat " + seat + " has version " + actual + " but " + expected
                                + " successful transitions were reported");
                    }
                });

        // I1-I3 and I8 in SQL, then the scenario's own checks.
        violations.addAll(HoldInvariants.check(jdbc, fixture.eventId()));
        violations.addAll(scenario.checkRace(race));
        return violations;
    }

    static String describe(CallResult result) {
        return result.action() + " -> " + (result.error() != null ? classify(result.error()) : result.outcome());
    }

    /** Names an exception by its SQLState when it came from Postgres (40P01 = deadlock). */
    static String classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                String meaning = switch (state) {
                    case "40P01" -> " (deadlock)";
                    case "55P03" -> " (lock not available)";
                    case "40001" -> " (serialization failure)";
                    default -> "";
                };
                return "SQLState " + state + meaning + ": " + firstLine(sql.getMessage());
            }
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + firstLine(root.getMessage());
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }

    // --- plan helpers for scenarios --------------------------------------------------------------

    /** A version-4 UUID drawn from the round's Random, so replaying a seed gives the same ids. */
    static UUID uuid(Random random) {
        long msb = (random.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** {@code count} distinct seats from the fixture, in random order. */
    static List<UUID> randomSeats(Random random, List<UUID> seats, int count) {
        List<UUID> shuffled = new ArrayList<>(seats);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, count));
    }
}
