package com.stampede.inventory.strategy;

import static com.stampede.inventory.strategy.ConcurrencyHarness.randomSeats;
import static com.stampede.inventory.strategy.ConcurrencyHarness.uuid;

import com.stampede.inventory.strategy.ConcurrencyHarness.Action;
import com.stampede.inventory.strategy.ConcurrencyHarness.CallResult;
import com.stampede.inventory.strategy.ConcurrencyHarness.Fixture;
import com.stampede.inventory.strategy.ConcurrencyHarness.RoundPlan;
import com.stampede.inventory.strategy.ConcurrencyHarness.Scenario;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** The three M1.3 scenarios. Every random choice comes from the round's Random, so seeds replay. */
final class HarnessScenarios {

    private HarnessScenarios() {
    }

    /** Every thread holds the same one seat. Exactly one may win. */
    static Scenario singleSeat() {
        return new Scenario() {
            @Override
            public String name() {
                return "singleSeat";
            }

            @Override
            public RoundPlan plan(Random random, Fixture fixture, int threads) {
                UUID seat = fixture.seatIds().get(random.nextInt(fixture.seatIds().size()));
                List<List<Action>> plans = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    plans.add(List.of(new Action.Hold(uuid(random), List.of(seat))));
                }
                return new RoundPlan(List.of(), plans);
            }

            @Override
            public List<String> checkRace(List<CallResult> race) {
                long winners = race.stream().filter(r -> r.outcome() instanceof Held).count();
                return winners == 1 ? List.of() : List.of("I4 " + winners + " winners for one seat (expected exactly 1)");
            }
        };
    }

    /**
     * Every thread holds 2 to 4 of the 6 seats, listed in random order, so requests overlap in many
     * ways (deadlock bait). The first transaction to commit always gets all its seats, so every round
     * has at least one winner; winners never share a seat (checked by I4).
     */
    static Scenario overlappingSets() {
        return new Scenario() {
            @Override
            public String name() {
                return "overlappingSets";
            }

            @Override
            public RoundPlan plan(Random random, Fixture fixture, int threads) {
                List<List<Action>> plans = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int count = 2 + random.nextInt(3);
                    plans.add(List.of(new Action.Hold(uuid(random), randomSeats(random, fixture.seatIds(), count))));
                }
                return new RoundPlan(List.of(), plans);
            }

            @Override
            public List<String> checkRace(List<CallResult> race) {
                boolean anyWinner = race.stream().anyMatch(r -> r.outcome() instanceof Held);
                return anyWinner ? List.of() : List.of("I4 no winner in a round of overlapping holds");
            }
        };
    }

    /**
     * Two holds exist before the race. Each thread then does one of: hold 1-3 seats and maybe release
     * that hold again; release one of the existing holds (several threads may release the same one);
     * or try to expire an existing hold that hasn't expired (must be refused). Mixes hold() and
     * release() lock paths on the same rows.
     */
    static Scenario churn() {
        return new Scenario() {
            @Override
            public String name() {
                return "churn";
            }

            @Override
            public RoundPlan plan(Random random, Fixture fixture, int threads) {
                List<UUID> seats = randomSeats(random, fixture.seatIds(), fixture.seatIds().size());
                int first = 1 + random.nextInt(2);
                int second = 1 + random.nextInt(2);
                List<Action.Hold> setup = List.of(
                        new Action.Hold(uuid(random), List.copyOf(seats.subList(0, first))),
                        new Action.Hold(uuid(random), List.copyOf(seats.subList(first, first + second))));

                List<List<Action>> plans = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int pick = random.nextInt(10);
                    if (pick < 5) {
                        UUID holdId = uuid(random);
                        List<Action> actions = new ArrayList<>();
                        actions.add(new Action.Hold(holdId, randomSeats(random, fixture.seatIds(), 1 + random.nextInt(3))));
                        if (random.nextBoolean()) {
                            actions.add(new Action.Release(holdId, releaseReason(random)));
                        }
                        plans.add(actions);
                    } else {
                        UUID existing = setup.get(random.nextInt(setup.size())).holdId();
                        ReleaseReason reason = pick < 8 ? releaseReason(random) : ReleaseReason.EXPIRED;
                        plans.add(List.of(new Action.Release(existing, reason)));
                    }
                }
                return new RoundPlan(setup, plans);
            }
        };
    }

    private static ReleaseReason releaseReason(Random random) {
        return random.nextBoolean() ? ReleaseReason.RELEASED : ReleaseReason.SAGA_FAILED;
    }
}
