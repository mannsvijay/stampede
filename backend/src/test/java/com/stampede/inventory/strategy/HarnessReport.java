package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.ConcurrencyHarness.CallResult;
import com.stampede.inventory.strategy.ConcurrencyHarness.Config;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts and failures of one scenario run, printed as a small table and written to a file. */
final class HarnessReport {

    /** One failed check, with the round's seed so its inputs can be replayed. */
    record Failure(int round, long seed, String violation) {
    }

    private static final int FAILURES_SHOWN = 10;

    private final StrategyKind strategy;
    private final String scenario;
    private final Config config;
    private final Map<String, Long> outcomes = new TreeMap<>();
    private final Map<String, Long> violationsByRule = new TreeMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private final List<Long> callNanos = new ArrayList<>();
    private int roundsRun;
    private int roundsFailed;
    private String abortReason;
    private long elapsedNanos;

    HarnessReport(StrategyKind strategy, String scenario, Config config) {
        this.strategy = strategy;
        this.scenario = scenario;
        this.config = config;
    }

    void record(int round, long seed, List<CallResult> setup, List<CallResult> race, List<String> violations) {
        roundsRun++;
        for (CallResult result : race) {
            outcomes.merge(label(result), 1L, Long::sum);
            callNanos.add(result.nanos());
        }
        if (!violations.isEmpty()) {
            roundsFailed++;
        }
        for (String violation : violations) {
            violationsByRule.merge(rule(violation), 1L, Long::sum);
            failures.add(new Failure(round, seed, violation));
        }
    }

    void abort(int round, long seed, String reason) {
        abortReason = "aborted in round " + round + " (seed " + seed + "): " + reason;
        failures.add(new Failure(round, seed, "ABORT " + reason));
    }

    void finish(long nanos) {
        elapsedNanos = nanos;
    }

    List<Failure> failures() {
        return failures;
    }

    long elapsedMillis() {
        return elapsedNanos / 1_000_000;
    }

    String render() {
        StringBuilder out = new StringBuilder();
        out.append("=== Concurrency harness: ").append(strategy).append(" / ").append(scenario).append(" ===\n");
        out.append(String.format("threads %d, rounds %d/%d run, base seed %d, %.1f s%n", config.threads(), roundsRun,
                config.rounds(), config.baseSeed(), elapsedNanos / 1e9));
        out.append(String.format("calls %d, per call p50 %.1f ms, p99 %.1f ms, max %.1f ms%n", callNanos.size(),
                percentile(50), percentile(99), percentile(100)));
        out.append("race outcomes (setup calls not counted):\n");
        outcomes.forEach((label, count) -> out.append(String.format("  %-48s %7d%n", label, count)));
        if (abortReason != null) {
            out.append("ABORTED: ").append(abortReason).append('\n');
        }
        if (failures.isEmpty()) {
            out.append("RESULT: ").append(roundsRun).append('/').append(config.rounds())
                    .append(" rounds clean, 0 invariant violations, 0 exceptions\n");
            return out.toString();
        }
        out.append("RESULT: FAILED in ").append(roundsFailed).append(" of ").append(roundsRun).append(" rounds\n");
        violationsByRule.forEach((rule, count) -> out.append(String.format("  %-48s %7d%n", rule, count)));
        out.append("first ").append(Math.min(FAILURES_SHOWN, failures.size())).append(" violations:\n");
        for (Failure failure : failures.subList(0, Math.min(FAILURES_SHOWN, failures.size()))) {
            out.append("  round ").append(failure.round()).append(" seed ").append(failure.seed()).append(": ")
                    .append(failure.violation()).append('\n');
        }
        out.append("replay a round's inputs (not its interleaving): -Dstampede.harness.seed=<seed> "
                + "-Dstampede.harness.rounds=1\n");
        return out.toString();
    }

    /** Writes the report to {@code <dir>/<strategy>-<scenario>.txt}. */
    void writeTo(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(strategy + "-" + scenario + ".txt"), render());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String label(CallResult result) {
        if (result.error() != null) {
            return "EXCEPTION " + ConcurrencyHarness.classify(result.error()).replaceAll(":.*", "");
        }
        return switch (result.outcome()) {
            case Held held -> "hold: Held";
            case Rejected rejected -> "hold: Rejected " + rejected.reason();
            case ReleaseOutcome release -> "release: " + release.getClass().getSimpleName();
            default -> "other: " + result.outcome();
        };
    }

    /** Groups violations by rule ("I4 ...") so the summary counts rules, not individual seats. */
    private static String rule(String violation) {
        String code = violation.split(" ", 2)[0];
        return switch (code) {
            case "I1" -> "I1 seat listed by two active holds";
            case "I2" -> "I2 held seat without its active hold";
            case "I3" -> "I3 active hold not owning its seat";
            case "I4" -> "I4 wrong number of winners";
            case "I5" -> "I5 database differs from outcomes";
            case "I6" -> "I6 version != transitions (lost update)";
            case "I7" -> "I7 exception";
            default -> violation.split(":", 2)[0];
        };
    }

    private double percentile(int p) {
        if (callNanos.isEmpty()) {
            return 0;
        }
        long[] sorted = callNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1e6;
    }
}
