package com.stampede.ticketing.wallet;

import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;
import com.stampede.shared.security.CurrentUser;
import com.stampede.ticketing.signing.TicketPayload;
import com.stampede.ticketing.signing.TicketSigner;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /me/tickets: the caller's wallet (PRD 10, D-018). Needs an access token (not in SecurityConfig's
 * public list), and the query is scoped to the token's user id, so nobody sees another holder's tickets.
 * <p>
 * The QR string is rebuilt from the stored row and its stored signature: nothing is signed here.
 * The frontend draws it as a QR code (M1.8).
 */
@Tag(name = "tickets")
@RestController
class MyTicketsController {

    enum TicketStatus { ISSUED, CHECKED_IN, REVOKED }

    record TicketResponse(UUID ticketId, UUID orderId, UUID eventId, UUID seatId,
                          @Schema(example = "A") String section,
                          @Schema(example = "3") String row,
                          @Schema(example = "5") int seatNumber,
                          TicketStatus status, int version, Instant issuedAt,
                          @Schema(description = "What the QR code encodes: base64url(payload).base64url(signature), "
                                  + "see docs/ticket-format.md") String qr) {
    }

    record MyTicketsResponse(List<TicketResponse> items) {
    }

    private final JdbcClient jdbc;

    MyTicketsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Live tickets (issued or checked in), newest first, then by seat label. */
    @GetMapping("/me/tickets")
    @ApiResponse(responseCode = "200", description = "The caller's tickets",
            content = @Content(schema = @Schema(implementation = MyTicketsResponse.class)))
    @ErrorResponses({ErrorCode.UNAUTHENTICATED})
    MyTicketsResponse myTickets(@AuthenticationPrincipal CurrentUser user) {
        List<TicketResponse> tickets = jdbc.sql("""
                        SELECT id, order_id, event_id, seat_id, holder_user_id, version, signature, status,
                               issued_at, key_id, section_code, row_label, seat_number
                        FROM tickets
                        WHERE holder_user_id = ? AND status <> 'REVOKED'
                        ORDER BY issued_at DESC, section_code, row_label, seat_number, id
                        """)
                .param(user.userId())
                .query((rs, n) -> {
                    TicketPayload payload = new TicketPayload(rs.getString("key_id"),
                            rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                            rs.getObject("seat_id", UUID.class), rs.getObject("holder_user_id", UUID.class),
                            rs.getObject("issued_at", OffsetDateTime.class).toInstant(), rs.getInt("version"));
                    return new TicketResponse(payload.ticketId(), rs.getObject("order_id", UUID.class),
                            payload.eventId(), payload.seatId(), rs.getString("section_code"),
                            rs.getString("row_label"), rs.getInt("seat_number"),
                            TicketStatus.valueOf(rs.getString("status")), payload.version(), payload.issuedAt(),
                            TicketSigner.qr(payload, rs.getBytes("signature")));
                })
                .list();
        return new MyTicketsResponse(tickets);
    }
}
