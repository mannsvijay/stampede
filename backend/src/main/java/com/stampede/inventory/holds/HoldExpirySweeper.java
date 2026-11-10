package com.stampede.inventory.holds;

import com.stampede.inventory.strategy.InventoryStrategies;
import com.stampede.inventory.strategy.ReleaseOutcome;
import com.stampede.inventory.strategy.ReleaseReason;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The Postgres safety net for hold expiry (PRD 6.3, D-016): frees holds whose time ran out.
 * <p>
 * <b>One hold per transaction, and not {@code @Transactional} itself</b> (D-015). The candidate ids
 * are read first, without locks; then each is passed to the strategy's {@code release(…, EXPIRED)},
 * which is its own transaction and locks in the global order (hold, seats, allowance). Releasing
 * several holds in one transaction would take seat locks hold by hold instead of in one seat order,
 * and one failure would roll back every other release.
 * <p>
 * <b>Several instances</b> may sweep at the same time (D-016 option a). Duplicates are harmless: the
 * release locks the hold row first, so the second sweeper waits, then sees the status the first wrote
 * and gets NotActive. A hold extended between the read and the release gets NotExpired and survives.
 */
@Component
class HoldExpirySweeper {


    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final JdbcClient jdbc;
    private final InventoryStrategies strategies;
    /** Candidates read per page, oldest first; a sweep reads pages until none are left. */
    private final int batchSize;

    HoldExpirySweeper(JdbcClient jdbc, InventoryStrategies strategies,
                      @Value("${stampede.holds.sweeper.batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.strategies = strategies;
        this.batchSize = batchSize;
    }

    /** What one sweep did. {@code skipped} = already ended or extended; {@code failed} = threw. */
    record SweepResult(int expired, int skipped, int failed) {
    }

    /**
     * Expires every ACTIVE hold past its expiry, each in its own transaction, reading candidates in
     * pages of {@code batchSize}.
     * <p>
     * Pages continue after the last hold seen, by (expires_at, id) ("keyset paging"), not from the
     * start. A hold whose release fails stays ACTIVE and expired; restarting from the oldest would
     * hand the same broken holds back first every time, and enough of them would fill every page
     * and stop healthy holds behind them from ever expiring.
     */
    SweepResult sweepOnce() {
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        Instant afterExpiry = null;
        UUID afterId = null;
        while (true) {
            List<ExpiredHold> page = expiredPage(afterExpiry, afterId);
            for (ExpiredHold hold : page) {
                switch (expire(hold)) {
                    case EXPIRED -> expired++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                }
            }
            if (page.size() < batchSize) {
                break;
            }
            ExpiredHold last = page.get(page.size() - 1);
            afterExpiry = last.expiresAt();
            afterId = last.holdId();
        }
        if (expired + failed > 0) {
            log.info("Hold sweep: {} expired, {} skipped, {} failed", expired, skipped, failed);
        }
        return new SweepResult(expired, skipped, failed);
    }

    /** One page of expired ACTIVE holds after (afterExpiry, afterId), oldest first. */
    private List<ExpiredHold> expiredPage(Instant afterExpiry, UUID afterId) {
        // Uses the partial index ix_holds_active_expires_at. The database clock decides (D-015).
        // The row comparison (a, b) > (x, y) means a > x, or a = x and b > y.
        return jdbc.sql("""
                        SELECT h.id, h.event_id, h.expires_at, e.strategy
                        FROM holds h JOIN events e ON e.id = h.event_id
                        WHERE h.status = 'ACTIVE' AND h.expires_at <= now()
                          AND (CAST(:afterExpiry AS timestamptz) IS NULL
                               OR (h.expires_at, h.id) > (CAST(:afterExpiry AS timestamptz), CAST(:afterId AS uuid)))
                        ORDER BY h.expires_at, h.id
                        LIMIT :batch
                        """)
                .param("afterExpiry", afterExpiry == null ? null : afterExpiry.atOffset(ZoneOffset.UTC))
                .param("afterId", afterId)
                .param("batch", batchSize)
                .query((rs, n) -> new ExpiredHold(rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("strategy")))
                .list();
    }

    private enum Result { EXPIRED, SKIPPED, FAILED }

    private Result expire(ExpiredHold hold) {
        try {
            ReleaseOutcome outcome = strategies.forKind(hold.strategy())
                    .release(hold.eventId(), hold.holdId(), ReleaseReason.EXPIRED);
            // NotActive: someone else ended it first. NotExpired: it was extended after we read it.
            return outcome instanceof ReleaseOutcome.Released ? Result.EXPIRED : Result.SKIPPED;
        } catch (RuntimeException e) {
            // One broken hold (e.g. drifted data, which release refuses to touch) must not stop the
            // others. It stays ACTIVE and is reported again on every sweep until someone fixes it.
            log.error("Could not expire hold {} of event {}", hold.holdId(), hold.eventId(), e);
            return Result.FAILED;
        }
    }

    private record ExpiredHold(UUID holdId, UUID eventId, Instant expiresAt, String strategy) {
    }
}
