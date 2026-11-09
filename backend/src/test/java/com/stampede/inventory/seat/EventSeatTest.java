package com.stampede.inventory.seat;

import static com.stampede.inventory.seat.SeatStatus.AVAILABLE;
import static com.stampede.inventory.seat.SeatStatus.HELD;
import static com.stampede.inventory.seat.SeatStatus.SOLD;
import static com.stampede.inventory.seat.SeatTransition.EXPIRE;
import static com.stampede.inventory.seat.SeatTransition.FAIL_SAGA;
import static com.stampede.inventory.seat.SeatTransition.HOLD;
import static com.stampede.inventory.seat.SeatTransition.RELEASE;
import static com.stampede.inventory.seat.SeatTransition.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.seat.IllegalSeatTransitionException.Reason;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class EventSeatTest {

    static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID SEAT = UUID.fromString("00000000-0000-0000-0000-000000000051");
    static final UUID HOLD_A = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");
    static final UUID HOLD_B = UUID.fromString("00000000-0000-0000-0000-00000000b0b0");
    static final UUID ORDER_A = UUID.fromString("00000000-0000-0000-0000-00000000a111");

    /** One row of the transition table. {@code to == null} means the transition must be rejected. */
    record Row(SeatStatus from, SeatTransition transition, SeatStatus to) {
        @Override
        public String toString() {
            return from + " --" + transition + "--> " + (to == null ? "rejected" : to);
        }
    }

    /**
     * The whole table, written out by hand from PRD 2 (D-003, D-014): 3 states x 5 transitions.
     * {@link #tableCoversEveryStatusAndTransition()} fails if a state or transition is added without
     * deciding its rows here.
     */
    static final List<Row> TABLE = List.of(
            new Row(AVAILABLE, HOLD, HELD),
            new Row(AVAILABLE, RELEASE, null),
            new Row(AVAILABLE, EXPIRE, null),
            new Row(AVAILABLE, FAIL_SAGA, null),
            new Row(AVAILABLE, SELL, null),

            new Row(HELD, HOLD, null),
            new Row(HELD, RELEASE, AVAILABLE),
            new Row(HELD, EXPIRE, AVAILABLE),
            new Row(HELD, FAIL_SAGA, AVAILABLE),
            new Row(HELD, SELL, SOLD),

            new Row(SOLD, HOLD, null),
            new Row(SOLD, RELEASE, null),
            new Row(SOLD, EXPIRE, null),
            new Row(SOLD, FAIL_SAGA, null),
            new Row(SOLD, SELL, null));

    static Stream<Row> legalRows() {
        return TABLE.stream().filter(r -> r.to() != null);
    }

    static Stream<Row> illegalRows() {
        return TABLE.stream().filter(r -> r.to() == null);
    }

    /** A seat in the given state with the strict pointers a transition would leave, at version 7. */
    static EventSeat seatIn(SeatStatus status) {
        return switch (status) {
            case AVAILABLE -> new EventSeat(EVENT, SEAT, AVAILABLE, null, null, 7);
            case HELD -> new EventSeat(EVENT, SEAT, HELD, HOLD_A, null, 7);
            case SOLD -> new EventSeat(EVENT, SEAT, SOLD, null, ORDER_A, 7);
        };
    }

    /** Applies a transition, naming the hold that owns the seat when it is HELD (HOLD_A). */
    static EventSeat apply(EventSeat seat, SeatTransition transition, UUID holdId) {
        return switch (transition) {
            case HOLD -> seat.hold(holdId);
            case RELEASE -> seat.release(holdId);
            case EXPIRE -> seat.expire(holdId);
            case FAIL_SAGA -> seat.failSaga(holdId);
            case SELL -> seat.sell(holdId, ORDER_A);
        };
    }

    @Test
    void tableCoversEveryStatusAndTransition() {
        Set<String> seen = new HashSet<>();
        for (Row row : TABLE) {
            assertThat(seen.add(row.from() + "/" + row.transition())).as("duplicate row %s", row).isTrue();
        }
        assertThat(seen).hasSize(SeatStatus.values().length * SeatTransition.values().length);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalRows")
    void legalTransitionMovesToTheTargetStateAndBumpsTheVersion(Row row) {
        EventSeat before = seatIn(row.from());

        EventSeat after = apply(before, row.transition(), HOLD_A);

        assertThat(after.status()).isEqualTo(row.to());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.eventId()).isEqualTo(EVENT);
        assertThat(after.seatId()).isEqualTo(SEAT);
        // The pointer is the current owner and nothing else (D-014).
        switch (row.to()) {
            case AVAILABLE -> {
                assertThat(after.holdId()).isNull();
                assertThat(after.orderId()).isNull();
            }
            case HELD -> {
                assertThat(after.holdId()).isEqualTo(HOLD_A);
                assertThat(after.orderId()).isNull();
            }
            case SOLD -> {
                assertThat(after.holdId()).as("sell clears the hold pointer").isNull();
                assertThat(after.orderId()).isEqualTo(ORDER_A);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalRows")
    void illegalTransitionIsRejectedAndChangesNothing(Row row) {
        EventSeat before = seatIn(row.from());

        assertThatThrownBy(() -> apply(before, row.transition(), HOLD_A))
                .isInstanceOfSatisfying(IllegalSeatTransitionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.WRONG_STATE);
                    assertThat(e.from()).isEqualTo(row.from());
                    assertThat(e.transition()).isEqualTo(row.transition());
                    assertThat(e.seatId()).isEqualTo(SEAT);
                })
                .hasMessage("Seat " + SEAT + " cannot " + row.transition() + " from " + row.from());
        assertThat(before).isEqualTo(seatIn(row.from()));
    }

    @ParameterizedTest
    @EnumSource(value = SeatTransition.class, names = {"RELEASE", "EXPIRE", "FAIL_SAGA", "SELL"})
    void transitionOutOfHeldByAnotherHoldIsRejected(SeatTransition transition) {
        EventSeat heldByA = seatIn(HELD);

        assertThatThrownBy(() -> apply(heldByA, transition, HOLD_B))
                .isInstanceOfSatisfying(IllegalSeatTransitionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.HOLD_MISMATCH);
                    assertThat(e.from()).isEqualTo(HELD);
                    assertThat(e.transition()).isEqualTo(transition);
                })
                .hasMessage("Seat " + SEAT + " cannot " + transition + ": it is held by hold " + HOLD_A
                        + ", not " + HOLD_B);
    }

    @Test
    void holdOnAHeldSeatIsRejectedEvenWithTheSameHold() {
        assertThatThrownBy(() -> seatIn(HELD).hold(HOLD_A))
                .isInstanceOfSatisfying(IllegalSeatTransitionException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.WRONG_STATE));
    }

    @Test
    void nullIdsAreProgrammingErrorsNotTransitions() {
        assertThatNullPointerException().isThrownBy(() -> seatIn(AVAILABLE).hold(null));
        assertThatNullPointerException().isThrownBy(() -> seatIn(HELD).release(null));
        assertThatNullPointerException().isThrownBy(() -> seatIn(HELD).expire(null));
        assertThatNullPointerException().isThrownBy(() -> seatIn(HELD).failSaga(null));
        assertThatNullPointerException().isThrownBy(() -> seatIn(HELD).sell(null, ORDER_A));
        assertThatNullPointerException().isThrownBy(() -> seatIn(HELD).sell(HOLD_A, null));
    }

    @Test
    void availableFactoryStartsAtVersionZeroWithNoOwner() {
        EventSeat seat = EventSeat.available(EVENT, SEAT);

        assertThat(seat).isEqualTo(new EventSeat(EVENT, SEAT, AVAILABLE, null, null, 0));
    }

    @Test
    void fullLifecycleHoldReleaseHoldSell() {
        EventSeat sold = EventSeat.available(EVENT, SEAT)
                .hold(HOLD_A)
                .expire(HOLD_A)
                .hold(HOLD_B)
                .sell(HOLD_B, ORDER_A);

        assertThat(sold).isEqualTo(new EventSeat(EVENT, SEAT, SOLD, null, ORDER_A, 4));
    }

    // --- Constructor = ck_event_seats_status_pointers ------------------------------------------

    /** Every status/pointer shape, and whether the database CHECK accepts it. */
    static Stream<Object[]> pointerShapes() {
        return Stream.of(
                new Object[] {AVAILABLE, null, null, true},
                new Object[] {AVAILABLE, HOLD_A, null, false},
                new Object[] {AVAILABLE, null, ORDER_A, false},
                new Object[] {AVAILABLE, HOLD_A, ORDER_A, false},
                new Object[] {HELD, null, null, false},
                new Object[] {HELD, HOLD_A, null, true},
                new Object[] {HELD, null, ORDER_A, false},
                new Object[] {HELD, HOLD_A, ORDER_A, true},   // loose shape the CHECK allows (point B)
                new Object[] {SOLD, null, null, false},
                new Object[] {SOLD, HOLD_A, null, false},
                new Object[] {SOLD, null, ORDER_A, true},
                new Object[] {SOLD, HOLD_A, ORDER_A, true});  // loose shape the CHECK allows
    }

    @Test
    void pointerShapesCoverEveryStatus() {
        Set<SeatStatus> statuses = EnumSet.noneOf(SeatStatus.class);
        pointerShapes().forEach(shape -> statuses.add((SeatStatus) shape[0]));
        assertThat(statuses).containsExactlyInAnyOrder(SeatStatus.values());
    }

    @ParameterizedTest(name = "{0} hold={1} order={2} accepted={3}")
    @MethodSource("pointerShapes")
    void constructorAcceptsExactlyWhatTheDatabaseCheckAccepts(SeatStatus status, UUID holdId, UUID orderId,
            boolean accepted) {
        if (accepted) {
            assertThat(new EventSeat(EVENT, SEAT, status, holdId, orderId, 0).status()).isEqualTo(status);
        } else {
            assertThatThrownBy(() -> new EventSeat(EVENT, SEAT, status, holdId, orderId, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is " + status);
        }
    }

    @Test
    void constructorRejectsNegativeVersionAndMissingIds() {
        assertThatThrownBy(() -> new EventSeat(EVENT, SEAT, AVAILABLE, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException().isThrownBy(() -> new EventSeat(null, SEAT, AVAILABLE, null, null, 0));
        assertThatNullPointerException().isThrownBy(() -> new EventSeat(EVENT, null, AVAILABLE, null, null, 0));
        assertThatNullPointerException().isThrownBy(() -> new EventSeat(EVENT, SEAT, null, null, null, 0));
    }

    @Test
    void releasingALooseHeldShapeClearsBothPointers() {
        // A HELD row with an order_id is legal in the database (point B, decided in M1.5). Freeing it
        // must still leave a valid AVAILABLE seat.
        EventSeat loose = new EventSeat(EVENT, SEAT, HELD, HOLD_A, ORDER_A, 3);

        assertThat(loose.release(HOLD_A)).isEqualTo(new EventSeat(EVENT, SEAT, AVAILABLE, null, null, 4));
    }
}
