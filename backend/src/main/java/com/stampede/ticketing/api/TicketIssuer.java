package com.stampede.ticketing.api;

import com.stampede.ticketing.signing.TicketPayload;
import com.stampede.ticketing.signing.TicketSigner;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues signed tickets for a PAID order: the checkout saga's TicketsIssued step (PRD 6.4, 6.9; D-018).
 * Owns the {@code tickets} table.
 * <p>
 * <b>Idempotent, decided by the database.</b> {@code uq_tickets_live_seat} allows one live
 * (non-REVOKED) ticket per seat, and each insert says {@code ON CONFLICT ... DO NOTHING}, so running
 * this twice, or twice at the same moment, leaves exactly one ticket per seat. When two calls race,
 * the second insert for a seat waits for the first transaction, then does nothing once it commits.
 * Afterwards every seat's live ticket must belong to <b>this</b> order; if one belongs to another order,
 * nothing is written (one transaction, all or nothing) and it throws.
 */
@Service
public class TicketIssuer {

    private final JdbcClient jdbc;
    private final TicketSigner signer;

    TicketIssuer(JdbcClient jdbc, TicketSigner signer) {
        this.jdbc = jdbc;
        this.signer = signer;
    }

    /**
     * @throws IllegalStateException if a seat already has a live ticket from a different order (an
     *                               invariant violation: a seat is sold once)
     */
    @Transactional
    public void issueForOrder(IssueTickets command) {
        // Whole seconds: the payload carries seconds, and the row must hold exactly what was signed.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // Seat id order, so two concurrent calls wait on the seats' index entries in the same order.
        List<IssueTickets.Seat> seats = command.seats().stream()
                .sorted(Comparator.comparing(IssueTickets.Seat::seatId))
                .toList();
        for (IssueTickets.Seat seat : seats) {
            TicketPayload payload = new TicketPayload(signer.keyId(), UUID.randomUUID(), command.eventId(),
                    seat.seatId(), command.holderUserId(), issuedAt, 1);
            jdbc.sql("""
                            INSERT INTO tickets (id, order_id, event_id, seat_id, holder_user_id, version, signature,
                                                 status, issued_at, key_id, section_code, row_label, seat_number)
                            VALUES (:id, :orderId, :eventId, :seatId, :holder, :version, :signature,
                                    'ISSUED', :issuedAt, :keyId, :section, :row, :number)
                            ON CONFLICT (event_id, seat_id) WHERE status <> 'REVOKED' DO NOTHING
                            """)
                    .param("id", payload.ticketId())
                    .param("orderId", command.orderId())
                    .param("eventId", payload.eventId())
                    .param("seatId", payload.seatId())
                    .param("holder", payload.holderUserId())
                    .param("version", payload.version())
                    .param("signature", signer.sign(payload))
                    .param("issuedAt", OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
                    .param("keyId", payload.keyId())
                    .param("section", seat.section())
                    .param("row", seat.row())
                    .param("number", seat.number())
                    .update();
        }

        // Whose live ticket is each seat now? A fresh snapshot (READ COMMITTED), so a concurrent call's
        // committed tickets are visible here.
        Map<UUID, UUID> orderBySeat = jdbc.sql("""
                        SELECT seat_id, order_id FROM tickets
                        WHERE event_id = :eventId AND seat_id = ANY (:seatIds) AND status <> 'REVOKED'
                        """)
                .param("eventId", command.eventId())
                .param("seatIds", seats.stream().map(IssueTickets.Seat::seatId).toArray(UUID[]::new))
                .query((rs, n) -> Map.entry(rs.getObject("seat_id", UUID.class), rs.getObject("order_id", UUID.class)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<UUID> notOurs = seats.stream()
                .map(IssueTickets.Seat::seatId)
                .filter(seatId -> !command.orderId().equals(orderBySeat.get(seatId)))
                .toList();
        if (!notOurs.isEmpty()) {
            throw new IllegalStateException("Order " + command.orderId() + ": seats " + notOurs
                    + " already have a live ticket from another order; no tickets issued");
        }
    }
}
