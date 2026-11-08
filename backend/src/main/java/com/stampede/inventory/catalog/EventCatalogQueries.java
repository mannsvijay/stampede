package com.stampede.inventory.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.stampede.inventory.catalog.EventViews.EventDetail;
import com.stampede.inventory.catalog.EventViews.EventStatus;
import com.stampede.inventory.catalog.EventViews.EventSummary;
import com.stampede.inventory.catalog.EventViews.PriceTier;
import com.stampede.inventory.catalog.EventViews.VenueRef;

/** Read-only SQL for the event board and the event page. Reads only inventory's own tables. */
@Component
class EventCatalogQueries {

    private final JdbcClient jdbc;

    EventCatalogQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upcoming drops for the board: published (not DRAFT), not ENDED, and not started yet. SOLD_OUT
     * stays on the board. Ordered by drop time, then id so equal drop times have a stable order.
     */
    List<EventSummary> upcoming(int limit) {
        return jdbc.sql("""
                SELECT e.id, e.title, e.status, e.drop_at, e.starts_at, e.per_user_limit,
                       v.id AS venue_id, v.name AS venue_name
                FROM events e
                JOIN venues v ON v.id = e.venue_id
                WHERE e.status NOT IN ('DRAFT', 'ENDED')
                  AND e.starts_at > now()
                ORDER BY e.drop_at, e.id
                LIMIT ?
                """)
                .param(limit)
                .query((rs, n) -> summary(rs))
                .list();
    }

    /**
     * One event with its venue and tiers, or empty if it doesn't exist or is a DRAFT.
     *
     * <p>A single statement (one row per tier, LEFT JOIN so an event without tiers still has a row)
     * rather than two queries: under READ COMMITTED every statement takes its own snapshot, so two
     * queries could see the event and its tiers at different moments.
     */
    Optional<EventDetail> find(UUID eventId, Instant serverTime) {
        List<Row> rows = jdbc.sql("""
                SELECT e.id, e.title, e.status, e.drop_at, e.starts_at, e.per_user_limit,
                       v.id AS venue_id, v.name AS venue_name,
                       t.id AS tier_id, t.name AS tier_name, t.price_paise
                FROM events e
                JOIN venues v           ON v.id = e.venue_id
                LEFT JOIN price_tiers t ON t.event_id = e.id
                WHERE e.id = ? AND e.status <> 'DRAFT'
                ORDER BY t.price_paise DESC, t.name
                """)
                .param(eventId)
                .query((rs, n) -> new Row(summary(rs), tier(rs)))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        EventSummary e = rows.get(0).event();  // the event columns repeat on every row
        List<PriceTier> tiers = rows.stream().map(Row::tier).filter(Objects::nonNull).toList();
        return Optional.of(new EventDetail(e.id(), e.title(), e.status(), e.dropAt(), e.startsAt(),
                e.perUserLimit(), e.venue(), tiers, serverTime));
    }

    private record Row(EventSummary event, PriceTier tier) {}

    private static EventSummary summary(ResultSet rs) throws SQLException {
        return new EventSummary(rs.getObject("id", UUID.class), rs.getString("title"), status(rs),
                instant(rs, "drop_at"), instant(rs, "starts_at"), rs.getInt("per_user_limit"), venue(rs));
    }

    /** Null when the event has no tiers (the LEFT JOIN found nothing). */
    private static PriceTier tier(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("tier_id", UUID.class);
        return id == null ? null : new PriceTier(id, rs.getString("tier_name"), rs.getLong("price_paise"));
    }

    private static EventStatus status(ResultSet rs) throws SQLException {
        return EventStatus.valueOf(rs.getString("status"));
    }

    private static VenueRef venue(ResultSet rs) throws SQLException {
        return new VenueRef(rs.getObject("venue_id", UUID.class), rs.getString("venue_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
