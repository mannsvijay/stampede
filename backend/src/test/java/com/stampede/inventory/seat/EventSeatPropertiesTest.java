package com.stampede.inventory.seat;

import static com.stampede.inventory.seat.SeatStatus.AVAILABLE;
import static com.stampede.inventory.seat.SeatStatus.HELD;
import static com.stampede.inventory.seat.SeatStatus.SOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.statistics.Statistics;

/**
 * Property-based tests for the seat state machine. jqwik generates a random valid starting seat and a
 * random list of operations, applies them one by one, and checks rules that must hold after every
 * step. When a rule breaks, jqwik shrinks the input to the smallest sequence that still breaks it.
 * <p>
 * The oracles below (the database CHECK and the transition table) are written out again here on
 * purpose instead of calling into {@link EventSeat}: a test that asks the class whether the class is
 * right can't fail.
 */
class EventSeatPropertiesTest {

    static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID SEAT = UUID.fromString("00000000-0000-0000-0000-000000000051");
    // Small id pools, so a generated operation often names the owning hold and often doesn't.
    static final UUID HOLD_A = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");
    static final UUID HOLD_B = UUID.fromString("00000000-0000-0000-0000-00000000b0b0");
    static final UUID ORDER_A = UUID.fromString("00000000-0000-0000-0000-00000000a111");
    static final UUID ORDER_B = UUID.fromString("00000000-0000-0000-0000-00000000b111");

    /** One generated operation: which transition, and the ids the caller passes to it. */
    record Op(SeatTransition transition, UUID holdId, UUID orderId) {
        @Override
        public String toString() {
            return transition + "(" + name(holdId) + (transition == SeatTransition.SELL ? ", " + name(orderId) : "")
                    + ")";
        }
    }

    /** The raw columns of an event_seats row, before we know whether the database would accept them. */
    record Row(SeatStatus status, UUID holdId, UUID orderId, long version) {
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Op> ops() {
        return Combinators.combine(
                        Arbitraries.of(SeatTransition.class),
                        Arbitraries.of(HOLD_A, HOLD_B),
                        Arbitraries.of(ORDER_A, ORDER_B))
                .as(Op::new);
    }

    /** Any row shape, valid or not: each pointer is NULL about a third of the time. */
    @Provide
    Arbitrary<Row> rows() {
        return Combinators.combine(
                        Arbitraries.of(SeatStatus.class),
                        Arbitraries.of(HOLD_A, HOLD_B).injectNull(0.34),
                        Arbitraries.of(ORDER_A, ORDER_B).injectNull(0.34),
                        Arbitraries.longs().between(0, 1_000))
                .as(Row::new);
    }

    /** A starting seat: any row the database CHECK accepts, including the loose shapes. */
    @Provide
    Arbitrary<EventSeat> startSeats() {
        return rows().filter(EventSeatPropertiesTest::databaseCheckAccepts)
                .map(r -> new EventSeat(EVENT, SEAT, r.status(), r.holdId(), r.orderId(), r.version()));
    }

    // --- Oracles, written independently of EventSeat -------------------------------------------

    /** ck_event_seats_status_pointers from V1__init.sql, translated literally. */
    static boolean databaseCheckAccepts(Row r) {
        return (r.status() == AVAILABLE && r.holdId() == null && r.orderId() == null)
                || (r.status() == HELD && r.holdId() != null)
                || (r.status() == SOLD && r.orderId() != null);
    }

    static boolean databaseCheckAccepts(EventSeat s) {
        return databaseCheckAccepts(new Row(s.status(), s.holdId(), s.orderId(), s.version()));
    }

    /** PRD 2 as a table: where a legal operation leads, or empty if it must be rejected. */
    static Optional<SeatStatus> expectedTarget(EventSeat seat, Op op) {
        boolean heldByThisHold = seat.status() == HELD && op.holdId().equals(seat.holdId());
        return switch (op.transition()) {
            case HOLD -> seat.status() == AVAILABLE ? Optional.of(HELD) : Optional.empty();
            case RELEASE, EXPIRE, FAIL_SAGA -> heldByThisHold ? Optional.of(AVAILABLE) : Optional.empty();
            case SELL -> heldByThisHold ? Optional.of(SOLD) : Optional.empty();
        };
    }

    static EventSeat apply(EventSeat seat, Op op) {
        return switch (op.transition()) {
            case HOLD -> seat.hold(op.holdId());
            case RELEASE -> seat.release(op.holdId());
            case EXPIRE -> seat.expire(op.holdId());
            case FAIL_SAGA -> seat.failSaga(op.holdId());
            case SELL -> seat.sell(op.holdId(), op.orderId());
        };
    }

    /** Applies an operation; a rejected one leaves the seat as it was (it is immutable). */
    static EventSeat applyOrKeep(EventSeat seat, Op op) {
        try {
            return apply(seat, op);
        } catch (IllegalSeatTransitionException rejected) {
            return seat;
        }
    }

    // --- Properties ------------------------------------------------------------------------------

    @Property
    void everyReachableSeatPassesTheDatabaseCheck(@ForAll("startSeats") EventSeat start,
            @ForAll @Size(max = 50) List<@From("ops") Op> ops) {
        EventSeat seat = start;
        for (Op op : ops) {
            seat = applyOrKeep(seat, op);
            assertThat(databaseCheckAccepts(seat)).as("after %s: %s", op, seat).isTrue();
        }
        Statistics.collect(seat.status());
    }

    @Property
    void soldIsFinal(@ForAll("startSeats") EventSeat start, @ForAll @Size(max = 50) List<@From("ops") Op> ops) {
        EventSeat seat = start;
        UUID soldTo = seat.status() == SOLD ? seat.orderId() : null;
        for (Op op : ops) {
            seat = applyOrKeep(seat, op);
            if (soldTo != null) {
                assertThat(seat.status()).as("a SOLD seat moved after %s", op).isEqualTo(SOLD);
                assertThat(seat.orderId()).as("a SOLD seat changed owner after %s", op).isEqualTo(soldTo);
            } else if (seat.status() == SOLD) {
                soldTo = seat.orderId();
            }
        }
        Statistics.label("ever sold").collect(soldTo != null);
    }

    @Property
    void everyStepFollowsTheTransitionTable(@ForAll("startSeats") EventSeat start,
            @ForAll @Size(max = 50) List<@From("ops") Op> ops) {
        EventSeat seat = start;
        for (Op op : ops) {
            EventSeat before = seat;
            Optional<SeatStatus> target = expectedTarget(before, op);
            if (target.isEmpty()) {
                assertThatThrownBy(() -> apply(before, op))
                        .as("%s on %s must be rejected", op, before)
                        .isInstanceOf(IllegalSeatTransitionException.class);
                Statistics.label("step").collect("rejected");
                continue;
            }
            seat = apply(before, op);
            Statistics.label("step").collect(before.status() + " -> " + seat.status());

            assertThat(seat.status()).as("%s on %s", op, before).isEqualTo(target.get());
            assertThat(seat.version()).as("version after %s", op).isEqualTo(before.version() + 1);
            assertThat(seat.eventId()).isEqualTo(EVENT);
            assertThat(seat.seatId()).isEqualTo(SEAT);
            // The pointer is the current owner and nothing else (D-014).
            switch (seat.status()) {
                case AVAILABLE -> assertThat(seat.holdId()).isNull();
                case HELD -> assertThat(seat.holdId()).isEqualTo(op.holdId());
                case SOLD -> assertThat(seat.orderId()).isEqualTo(op.orderId());
            }
            assertThat(seat.status() == HELD ? seat.orderId() : seat.holdId()).isNull();
        }
    }

    @Property
    void constructorAcceptsExactlyWhatTheDatabaseCheckAccepts(@ForAll("rows") Row row) {
        boolean constructed;
        try {
            new EventSeat(EVENT, SEAT, row.status(), row.holdId(), row.orderId(), row.version());
            constructed = true;
        } catch (IllegalArgumentException rejected) {
            constructed = false;
        }
        assertThat(constructed).as("%s", row).isEqualTo(databaseCheckAccepts(row));
    }

    private static String name(UUID id) {
        if (HOLD_A.equals(id)) return "HOLD_A";
        if (HOLD_B.equals(id)) return "HOLD_B";
        if (ORDER_A.equals(id)) return "ORDER_A";
        if (ORDER_B.equals(id)) return "ORDER_B";
        return String.valueOf(id);
    }
}
