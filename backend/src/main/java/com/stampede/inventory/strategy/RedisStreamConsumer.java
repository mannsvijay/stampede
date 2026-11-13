package com.stampede.inventory.strategy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the inventory stream and applies each entry to Postgres (D-022, M2.2b): the other half of
 * the "outbox inside Redis" that hold.lua and release.lua write.
 * <p>
 * <b>Consumer group.</b> Every instance joins the group {@link RedisKeys#GROUP} under its own name
 * (decision 1a). Redis hands each new entry to exactly one consumer and remembers it in that
 * consumer's pending list until it is acknowledged ({@code XACK}). An entry is acknowledged only
 * after its Postgres transaction committed, so delivery is at-least-once and {@link RedisHoldWriter}
 * makes every apply idempotent.
 * <p>
 * <b>Crashes.</b> An entry whose consumer died stays pending. {@link #reclaimOnce} takes over entries
 * idle longer than {@code claim-min-idle} ({@code XCLAIM}), including this consumer's own failed ones.
 * A restarted consumer first re-reads its own pending list ({@link #readOwnPending}).
 * <p>
 * <b>Failures.</b> A transient failure (Postgres or Redis unreachable, a timeout) stops the batch and
 * is rethrown: the caller backs off, nothing is acknowledged, and it never counts towards giving up.
 * Any other failure leaves the entry pending for a retry; once it has been delivered
 * {@code max-deliveries} times, it is copied to the dead-letter stream and acknowledged (decision 2a).
 * The reconciler then repairs whatever state the entry would have written.
 * <p>
 * Every method here is called by one thread at a time per consumer name ({@link RedisConsumerLifecycle},
 * or a test).
 */
@Component
class RedisStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);
    private static final int DEAD_LETTER_MAX = 10_000;
    private static final int LAG_SCAN_MAX = 10_000;

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final RedisInventoryProperties.Consumer settings;
    private final RedisHoldWriter writer;
    private final RedisHoldStore store;
    private final RedisStreamMetrics metrics;
    private final String defaultName;

    RedisStreamConsumer(StringRedisTemplate redis, RedisInventoryProperties properties, RedisHoldWriter writer,
                        RedisHoldStore store, RedisStreamMetrics metrics) {
        this.redis = redis;
        this.keys = new RedisKeys(properties.keyPrefix());
        this.settings = properties.consumer();
        this.writer = writer;
        this.store = store;
        this.metrics = metrics;
        this.defaultName = settings.name().isBlank() ? hostName() : settings.name();
    }

    /** This instance's consumer name: the configured one, or the host name. */
    String name() {
        return defaultName;
    }

    /** Lag as the metrics report it. */
    record Lag(long entries, long oldestAgeMillis) {
    }

    /** Creates the stream and the group if they don't exist (XGROUP CREATE ... 0 MKSTREAM). */
    void ensureGroup() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(bytes(keys.stream()), RedisKeys.GROUP, ReadOffset.from("0"), true));
        } catch (RedisSystemException e) {
            if (!message(e).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    /**
     * Reads new entries ({@code >}) for this consumer, waiting up to {@code block} for the first one,
     * and applies them in order. Returns how many entries were read.
     */
    int pollOnce(String consumer) {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = readNew(consumer);
        } catch (RedisSystemException e) {
            if (!message(e).contains("NOGROUP")) {
                throw e;
            }
            // Redis lost the stream (a restart without its data, or a wipe): create the group again,
            // from the start of whatever the stream holds now, and read.
            log.warn("Inventory stream group is gone (Redis lost its data?); creating it again");
            ensureGroup();
            records = readNew(consumer);
        }
        return applyAll(consumer, records);
    }

    private List<MapRecord<String, Object, Object>> readNew(String consumer) {
        return stream().read(Consumer.from(RedisKeys.GROUP, consumer),
                StreamReadOptions.empty().count(settings.batchSize()).block(settings.block()),
                StreamOffset.create(keys.stream(), ReadOffset.lastConsumed()));
    }

    /**
     * Re-reads entries this consumer was given but never acknowledged (after a restart). Reading the
     * pending list from id 0 hands those entries out again without waiting for them to go idle.
     */
    int readOwnPending(String consumer) {
        int total = 0;
        String after = "0";
        while (true) {
            List<MapRecord<String, Object, Object>> records = stream().read(Consumer.from(RedisKeys.GROUP, consumer),
                    StreamReadOptions.empty().count(settings.batchSize()),
                    StreamOffset.create(keys.stream(), ReadOffset.from(after)));
            if (records == null || records.isEmpty()) {
                return total;
            }
            applyAll(consumer, records);
            total += records.size();
            after = records.get(records.size() - 1).getId().getValue();
        }
    }

    /**
     * Takes over pending entries idle for at least {@code claim-min-idle}, from any consumer of the
     * group (a crashed one, or this one after a failure), and applies them. Returns how many it claimed.
     */
    int reclaimOnce(String consumer) {
        PendingMessages pending = stream().pending(keys.stream(), RedisKeys.GROUP, Range.unbounded(),
                settings.batchSize());
        Duration minIdle = settings.claimMinIdle();
        RecordId[] idle = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);
        if (idle.length == 0) {
            return 0;
        }
        // XCLAIM re-checks the idle time, so two instances reclaiming at once don't both get an entry.
        List<MapRecord<String, Object, Object>> claimed = stream().claim(keys.stream(), RedisKeys.GROUP, consumer,
                minIdle, idle);
        applyAll(consumer, claimed);
        return claimed.size();
    }

    /**
     * Deletes every entry before the oldest one not yet acknowledged ({@code XTRIM MINID}): the oldest
     * pending entry, or the group's last-delivered entry when nothing is pending (entries after it are
     * not delivered yet and stay). Exact, not approximate ({@code =}). Returns how many were deleted.
     */
    long trimOnce() {
        String minId = oldestKeptId();
        if (minId == null) {
            return 0;
        }
        Object trimmed = redis.execute((RedisCallback<Object>) connection -> connection.execute("XTRIM",
                bytes(keys.stream()), bytes("MINID"), bytes("="), bytes(minId)));
        return trimmed instanceof Long count ? count : 0;
    }

    /** Recomputes the lag and the oldest unacknowledged entry's age and publishes both as gauges. */
    Lag refreshLag() {
        XInfoGroup group = group();
        if (group == null) {
            metrics.lag(0, 0);
            return new Lag(0, 0);
        }
        PendingMessagesSummary summary = stream().pending(keys.stream(), RedisKeys.GROUP);
        long pendingCount = summary == null ? 0 : summary.getTotalPendingMessages();
        List<MapRecord<String, Object, Object>> undelivered = undelivered(group.lastDeliveredId());
        long entries = pendingCount + undelivered.size();
        String oldest = pendingCount > 0 ? summary.minMessageId()
                : undelivered.isEmpty() ? null : undelivered.get(0).getId().getValue();
        long age = oldest == null ? 0 : Math.max(0, store.nowMillis() - RecordId.of(oldest).getTimestamp());
        metrics.lag(entries, age);
        return new Lag(entries, age);
    }

    /** Recognises the failures worth waiting out: something is unreachable or slow, not wrong. */
    static boolean isTransient(Throwable failure) {
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (e instanceof TransientDataAccessException || e instanceof RecoverableDataAccessException
                    || e instanceof DataAccessResourceFailureException || e instanceof SQLTransientException) {
                return true;
            }
        }
        return false;
    }

    // ---- applying ----

    private int applyAll(String consumer, List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            return 0;
        }
        for (MapRecord<String, Object, Object> record : records) {
            apply(consumer, record);
        }
        return records.size();
    }

    private void apply(String consumer, MapRecord<String, Object, Object> record) {
        String id = record.getId().getValue();
        try {
            writer.apply(StreamEntry.parse(id, record.getValue()));
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                throw e;   // stop the batch; the caller backs off and nothing more is acknowledged
            }
            long deliveries = deliveries(id);
            if (deliveries < settings.maxDeliveries()) {
                log.warn("Inventory stream entry {} failed (delivery {} of {}); it stays pending and is retried",
                        id, deliveries, settings.maxDeliveries(), e);
                return;
            }
            deadLetter(consumer, record, e, deliveries);
        }
        stream().acknowledge(keys.stream(), RedisKeys.GROUP, id);
    }

    /** How many times Redis has delivered this entry (XPENDING); at least 1 for an entry being applied. */
    private long deliveries(String id) {
        PendingMessages pending = stream().pending(keys.stream(), RedisKeys.GROUP, Range.closed(id, id), 1);
        return pending.isEmpty() ? 1 : pending.get(0).getTotalDeliveryCount();
    }

    private void deadLetter(String consumer, MapRecord<String, Object, Object> record, RuntimeException e,
                            long deliveries) {
        Map<String, String> fields = new HashMap<>();
        record.getValue().forEach((field, value) -> fields.put(field.toString(), String.valueOf(value)));
        fields.put("sourceId", record.getId().getValue());
        fields.put("consumer", consumer);
        fields.put("deliveries", Long.toString(deliveries));
        String error = e.getClass().getName() + ": " + e.getMessage();
        fields.put("error", error.length() > 500 ? error.substring(0, 500) : error);
        stream().add(StreamRecords.newRecord().in(keys.deadLetters()).ofMap(fields));
        stream().trim(keys.deadLetters(), DEAD_LETTER_MAX, true);
        metrics.deadLetter();
        log.error("Inventory stream entry {} failed {} times; moved to {} and acknowledged. The reconciler "
                + "repairs the state it would have written.", record.getId().getValue(), deliveries,
                keys.deadLetters(), e);
    }

    // ---- reading the group's position ----

    private String oldestKeptId() {
        XInfoGroup group = group();
        if (group == null) {
            return null;
        }
        PendingMessagesSummary summary = stream().pending(keys.stream(), RedisKeys.GROUP);
        if (summary != null && summary.getTotalPendingMessages() > 0) {
            return summary.minMessageId();
        }
        return group.lastDeliveredId();
    }

    /** Entries after the group's last-delivered id (not handed to any consumer yet), up to a cap. */
    private List<MapRecord<String, Object, Object>> undelivered(String lastDeliveredId) {
        List<MapRecord<String, Object, Object>> fromLast = stream().range(keys.stream(),
                Range.rightUnbounded(Range.Bound.inclusive(lastDeliveredId)), Limit.limit().count(LAG_SCAN_MAX + 1));
        if (fromLast == null) {
            return List.of();
        }
        return fromLast.stream().filter(record -> !record.getId().getValue().equals(lastDeliveredId)).toList();
    }

    private XInfoGroup group() {
        if (!Boolean.TRUE.equals(redis.hasKey(keys.stream()))) {
            return null;
        }
        try {
            return stream().groups(keys.stream()).stream()
                    .filter(group -> RedisKeys.GROUP.equals(group.groupName()))
                    .findFirst()
                    .orElse(null);
        } catch (RedisSystemException e) {
            return null;
        }
    }

    private StreamOperations<String, Object, Object> stream() {
        return redis.opsForStream();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String message(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(' ');
        }
        return all.toString();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "stampede-" + UUID.randomUUID();
        }
    }
}
