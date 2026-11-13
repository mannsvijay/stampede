package com.stampede.inventory.strategy;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.stereotype.Component;

/**
 * Runs this instance's stream consumer on one background thread (D-022, M2.2b), on every instance
 * (decision 1a). Off when {@code stampede.inventory.redis.consumer.enabled=false}; tests call
 * {@link RedisStreamConsumer} directly instead.
 * <p>
 * The loop: create the group if needed, re-read this consumer's own pending entries, then repeatedly
 * read new entries (a blocking read of up to {@code block}). Every 10 s it also reclaims idle entries
 * and trims the stream; after every read it refreshes the lag gauges. Any failure is logged and waited
 * out with a backoff of 1 s doubling to 5 s; nothing unapplied is ever acknowledged.
 * <p>
 * The thread keeps one Redis connection bound to itself for its whole life, so the blocking reads
 * reuse one dedicated Lettuce connection instead of opening a new one every second.
 */
@Component
@ConditionalOnProperty(name = "stampede.inventory.redis.consumer.enabled", havingValue = "true", matchIfMissing = true)
class RedisConsumerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisConsumerLifecycle.class);
    private static final Duration HOUSEKEEPING = Duration.ofSeconds(10);
    private static final long MAX_BACKOFF_MS = 5_000;

    private final RedisStreamConsumer consumer;
    private final RedisConnectionFactory connections;
    private volatile boolean running;
    private Thread thread;

    RedisConsumerLifecycle(RedisStreamConsumer consumer, RedisConnectionFactory connections) {
        this.consumer = consumer;
        this.connections = connections;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "inventory-stream-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("Inventory stream consumer '{}' started", consumer.name());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        String name = consumer.name();
        boolean started = false;
        long nextHousekeeping = 0;
        long backoffMs = 0;
        RedisConnectionUtils.bindConnection(connections);
        try {
            while (running) {
                try {
                    if (!started) {
                        consumer.ensureGroup();
                        consumer.readOwnPending(name);
                        started = true;
                    }
                    if (System.currentTimeMillis() >= nextHousekeeping) {
                        consumer.reclaimOnce(name);
                        consumer.trimOnce();
                        nextHousekeeping = System.currentTimeMillis() + HOUSEKEEPING.toMillis();
                    }
                    consumer.pollOnce(name);
                    consumer.refreshLag();
                    backoffMs = 0;
                } catch (RuntimeException e) {
                    if (!running) {
                        break;
                    }
                    backoffMs = Math.min(MAX_BACKOFF_MS, Math.max(1_000, backoffMs * 2));
                    if (RedisStreamConsumer.isTransient(e)) {
                        log.warn("Inventory stream consumer: {}; retrying in {} ms", e.getMessage(), backoffMs);
                    } else {
                        log.error("Inventory stream consumer failed; retrying in {} ms", backoffMs, e);
                    }
                    sleep(backoffMs);
                    // A broken connection stays broken while bound: take a fresh one.
                    RedisConnectionUtils.unbindConnection(connections);
                    RedisConnectionUtils.bindConnection(connections);
                }
            }
        } finally {
            RedisConnectionUtils.unbindConnection(connections);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
