package com.stampede.inventory.api;

import com.stampede.inventory.api.HoldResult.HoldView;
import com.stampede.inventory.strategy.HoldOutcome;
import com.stampede.inventory.strategy.HoldRequest;
import com.stampede.inventory.strategy.InventoryStrategies;
import com.stampede.inventory.strategy.ReleaseOutcome;
import com.stampede.inventory.strategy.ReleaseReason;
import com.stampede.inventory.strategy.SellOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Holding and cancelling seats (PRD 6.3, D-016). Picks the event's inventory strategy and turns its
 * outcomes into results callers can act on.
 * <p>
 * <b>Deliberately not {@code @Transactional}</b> (D-015): each strategy call is its own transaction
 * (the strategy's REQUIRED propagation). If this class opened one, the strategy would join it, row
 * locks would be held for longer, and a later failure here would roll back a hold the strategy
 * already decided. The reads here run outside any transaction; each is explained where it happens.
 */
@Service
public class HoldService {

    /** How long a hold lasts (PRD 6.3). */
    public static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final InventoryStrategies strategies;

    HoldService(JdbcClient jdbc, InventoryStrategies strategies) {
        this.jdbc = jdbc;
        this.strategies = strategies;
    }

    /**
     * Holds every seat or none.
     *
     * @param seatIds at least one, no duplicates (the controller validates; {@link HoldRequest} throws
     *                IllegalArgumentException otherwise)
     */
    public HoldResult hold(UUID userId, UUID eventId, List<UUID> seatIds) {
        // Not locked: status and drop time can change right after this check (an admin pausing the
        // event), and the hold still goes through. Same window as any check-then-act outside the
        // strategy; pausing a sale is a later control-room feature (M5.5) that can move this check
        // into the strategy if it matters.
        Optional<EventRow> event = jdbc.sql("""
                        SELECT strategy, status, per_user_limit, drop_at <= now() AS dropped
                        FROM events WHERE id = ?
                        """)
                .param(eventId)
                .query((rs, n) -> new EventRow(rs.getString("strategy"), rs.getString("status"),
                        rs.getInt("per_user_limit"), rs.getBoolean("dropped")))
                .optional();
        if (event.isEmpty() || event.get().status().equals("DRAFT")) {
            return new HoldResult.EventNotFound();
        }
        if (!event.get().saleOpen()) {
            return new HoldResult.SaleNotOpen();
        }

        // A new id for every attempt (D-015): reusing one is a programming error the holds primary
        // key rejects. A retried request is recognised by its seats instead (below).
        HoldRequest request = new HoldRequest(UUID.randomUUID(), eventId, userId, seatIds, HOLD_DURATION);
        HoldOutcome outcome = strategies.forKind(event.get().strategy()).hold(request);

        return switch (outcome) {
            case HoldOutcome.Held held -> new HoldResult.Created(
                    new HoldView(held.holdId(), eventId, held.seatIds(), held.expiresAt()));
            case HoldOutcome.Rejected rejected -> switch (rejected.reason()) {
                case UNKNOWN_SEAT -> new HoldResult.UnknownSeats(rejected.seatIds());
                case SEAT_TAKEN -> existingHoldOnExactly(userId, eventId, seatIds)
                        .<HoldResult>map(HoldResult.Existing::new)
                        .orElseGet(() -> new HoldResult.SeatTaken(rejected.seatIds(), rejected.takenAgoMs()));
                case LIMIT_EXCEEDED -> new HoldResult.LimitExceeded(rejected.seatIds(), event.get().perUserLimit());
            };
        };
    }

    /**
     * Cancels the caller's hold ({@code DELETE /holds/{id}}). Someone else's hold is reported exactly
     * like an unknown id, so the endpoint never tells a caller that another user's hold exists.
     */
    public CancelResult cancel(UUID userId, UUID holdId) {
        // Ownership first. Not locked: user_id and event_id never change after insert, so this read
        // can't go stale; the status, which can, is re-checked by the strategy under the hold lock.
        Optional<HoldOwner> owner = owner(holdId);
        if (owner.isEmpty() || !owner.get().userId().equals(userId)) {
            return new CancelResult.NotFound();
        }

        ReleaseOutcome outcome = strategies.forKind(owner.get().strategy())
                .release(owner.get().eventId(), holdId, ReleaseReason.RELEASED);
        return switch (outcome) {
            case ReleaseOutcome.Released released -> new CancelResult.Cancelled();
            case ReleaseOutcome.NotActive notActive -> notActive.holdStatus().equals("CONVERTED")
                    ? new CancelResult.Converted()
                    : new CancelResult.AlreadyEnded(notActive.holdStatus());
            // Holds are never deleted, and the ownership read found this one.
            case ReleaseOutcome.NotFound notFound ->
                    throw new IllegalStateException("Hold " + holdId + " disappeared during cancel");
            // Only a release with reason EXPIRED can answer NotExpired.
            case ReleaseOutcome.NotExpired notExpired ->
                    throw new IllegalStateException("NotExpired for a RELEASED release of hold " + holdId);
        };
    }

    /**
     * Checkout step 0, HoldVerified (M1.5, D-017): whether the caller's hold can become an order,
     * with each seat's tier price. Someone else's hold is reported exactly like an unknown id.
     * <p>
     * Not locked: the hold can expire or be cancelled right after this read. That is fine because
     * {@link #sellForOrder} checks again under the hold lock, and the checkout handles the answer.
     * The prices can't go stale: a seat's tier and a tier's price never change after seeding.
     */
    public CheckoutHold checkoutHold(UUID userId, UUID holdId) {
        Optional<CheckoutRow> hold = jdbc.sql("""
                        SELECT user_id, event_id, status, expires_at, expires_at <= now() AS expired
                        FROM holds WHERE id = ?
                        """)
                .param(holdId)
                .query((rs, n) -> new CheckoutRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getBoolean("expired")))
                .optional();
        if (hold.isEmpty() || !hold.get().userId().equals(userId)) {
            return new CheckoutHold.NotFound();
        }
        CheckoutRow row = hold.get();
        if (row.status().equals("CONVERTED")) {
            return new CheckoutHold.Converted();
        }
        if (!row.status().equals("ACTIVE") || row.expired()) {
            return new CheckoutHold.Expired();
        }
        // The seats the hold lists, priced by their tier. A HELD seat's tier is fixed, so reading
        // through holds.seat_ids (not event_seats.hold_id) is enough here; sell() checks ownership.
        List<CheckoutHold.SeatPrice> seats = jdbc.sql("""
                        SELECT es.seat_id, pt.price_paise
                        FROM holds h
                        JOIN event_seats es ON es.event_id = h.event_id AND es.seat_id = ANY (h.seat_ids)
                        JOIN price_tiers pt ON pt.id = es.tier_id
                        WHERE h.id = ?
                        ORDER BY es.seat_id
                        """)
                .param(holdId)
                .query((rs, n) -> new CheckoutHold.SeatPrice(rs.getObject("seat_id", UUID.class),
                        rs.getLong("price_paise")))
                .list();
        return new CheckoutHold.Orderable(holdId, row.eventId(), seats, row.expiresAt());
    }

    /**
     * Checkout step 3, SeatsSold: sells the hold's seats to the order through the event's strategy,
     * in the strategy's own transaction (D-015: this class is not transactional). The order row must
     * be committed already ({@code event_seats.order_id} has a foreign key to it). Ownership was
     * checked by {@link #checkoutHold}; the caller (booking) passes only its own order's hold.
     */
    public SellResult sellForOrder(UUID holdId, UUID orderId) {
        HoldOwner owner = owner(holdId)
                .orElseThrow(() -> new IllegalStateException("No hold " + holdId + " to sell to order " + orderId));
        SellOutcome outcome = strategies.forKind(owner.strategy()).sell(owner.eventId(), holdId, orderId);
        return switch (outcome) {
            case SellOutcome.Sold sold -> new SellResult.Sold(sold.seatIds());
            case SellOutcome.HoldExpired expired -> new SellResult.NoLongerHeld("ACTIVE (expired)");
            case SellOutcome.NotActive notActive -> notActive.holdStatus().equals("CONVERTED")
                    ? new SellResult.AlreadySold()
                    : new SellResult.NoLongerHeld(notActive.holdStatus());
            case SellOutcome.NotFound notFound ->
                    throw new IllegalStateException("Hold " + holdId + " disappeared during sell");
        };
    }

    /**
     * Checkout compensation: frees the hold's seats because the checkout failed (seat transition
     * {@code failSaga}, hold status RELEASED, allowance given back). The strategy decides under the
     * hold row lock, the same lock {@link #sellForOrder} takes, so the answer is definitive: if it is
     * {@link CheckoutRelease#CONVERTED}, the sale committed and nothing must be undone.
     */
    public CheckoutRelease releaseForFailedCheckout(UUID holdId) {
        HoldOwner owner = owner(holdId)
                .orElseThrow(() -> new IllegalStateException("No hold " + holdId + " to release"));
        ReleaseOutcome outcome = strategies.forKind(owner.strategy())
                .release(owner.eventId(), holdId, ReleaseReason.SAGA_FAILED);
        return switch (outcome) {
            case ReleaseOutcome.Released released -> CheckoutRelease.RELEASED;
            case ReleaseOutcome.NotActive notActive -> notActive.holdStatus().equals("CONVERTED")
                    ? CheckoutRelease.CONVERTED
                    : CheckoutRelease.ALREADY_ENDED;
            case ReleaseOutcome.NotFound notFound ->
                    throw new IllegalStateException("Hold " + holdId + " disappeared during release");
            case ReleaseOutcome.NotExpired notExpired ->
                    throw new IllegalStateException("NotExpired for a SAGA_FAILED release of hold " + holdId);
        };
    }

    /** The hold's event and that event's strategy. user_id and event_id never change after insert. */
    private Optional<HoldOwner> owner(UUID holdId) {
        return jdbc.sql("""
                        SELECT h.event_id, h.user_id, e.strategy
                        FROM holds h JOIN events e ON e.id = h.event_id
                        WHERE h.id = ?
                        """)
                .param(holdId)
                .query((rs, n) -> new HoldOwner(rs.getObject("event_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("strategy")))
                .optional();
    }

    /**
     * The caller's ACTIVE, unexpired hold covering exactly these seats, if any (D-016's retry rule).
     * Runs after the strategy's transaction, unlocked: if the hold ends in between, the caller gets
     * SEAT_TAKEN, which is still true for whoever holds the seats now.
     */
    private Optional<HoldView> existingHoldOnExactly(UUID userId, UUID eventId, List<UUID> seatIds) {
        // @> and <@ are array containment: both together mean the same set of seats, in any order.
        return jdbc.sql("""
                        SELECT id, seat_ids, expires_at FROM holds
                        WHERE event_id = :eventId AND user_id = :userId
                          AND status = 'ACTIVE' AND expires_at > now()
                          AND seat_ids @> CAST(:seatIds AS uuid[]) AND seat_ids <@ CAST(:seatIds AS uuid[])
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .param("seatIds", seatIds.toArray(UUID[]::new))
                .query((rs, n) -> new HoldView(rs.getObject("id", UUID.class), eventId, uuids(rs),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private static List<UUID> uuids(ResultSet rs) throws SQLException {
        return Arrays.asList((UUID[]) rs.getArray("seat_ids").getArray());
    }

    private record EventRow(String strategy, String status, int perUserLimit, boolean dropped) {
        /** On sale: after the drop, while SCHEDULED or LIVE (D-016). */
        boolean saleOpen() {
            return dropped && (status.equals("SCHEDULED") || status.equals("LIVE"));
        }
    }

    private record HoldOwner(UUID eventId, UUID userId, String strategy) {
    }

    private record CheckoutRow(UUID userId, UUID eventId, String status, Instant expiresAt, boolean expired) {
    }
}
