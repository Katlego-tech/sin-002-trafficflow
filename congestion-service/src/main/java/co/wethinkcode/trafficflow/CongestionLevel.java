package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.LastPublished.ReadFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.InstantSource;

/**
 * The city-wide Congestion Level: 0 (clear) to 8 (gridlock).
 *
 * <p>Unknown until it has been read back from the last change published, so a restart picks up
 * where it left off rather than claiming 0. Nothing ever published means it starts at 0.
 */
final class CongestionLevel {

    private static final Logger log = LoggerFactory.getLogger(CongestionLevel.class);

    static final int MIN_LEVEL = 0;
    static final int MAX_LEVEL = 8;

    /** The current level, and when it last changed: null if it never has. */
    record Level(int level, String updatedAt) {
    }

    /** The level could not be read back, so this service can't say what it is. */
    static class LevelUnknown extends RuntimeException {
        LevelUnknown(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final CongestionPublisher publisher;
    private final LastPublished lastPublished;
    private final InstantSource clock;
    private volatile Level current; // null until read back

    CongestionLevel(CongestionPublisher publisher, LastPublished lastPublished, InstantSource clock) {
        this.publisher = publisher;
        this.lastPublished = lastPublished;
        this.clock = clock;
    }

    /** @throws LevelUnknown if the level could not be read back; the next call tries again */
    Level current() {
        Level level = current;
        return level != null ? level : recover();
    }

    /**
     * Publishes the change first and records it second: if it can't be published, the level stays
     * as it was, so this service never holds a level its subscribers weren't told about. Setting
     * the level it already has changes and publishes nothing, not even {@code updatedAt}.
     *
     * @throws CongestionPublisher.PublishFailed if the change could not be published
     * @throws LevelUnknown if the level before it could not be read back; nothing is published
     */
    synchronized Level set(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be from " + MIN_LEVEL + " to " + MAX_LEVEL + ", got " + level);
        }
        Level before = current();
        if (level == before.level()) {
            return before;
        }
        String now = clock.instant().toString();
        publisher.publish(new CongestionChanged(level, before.level(), now));
        current = new Level(level, now);
        return current;
    }

    private synchronized Level recover() {
        if (current != null) {
            return current;
        }
        try {
            current = lastPublished.read()
                    .map(change -> new Level(change.level(), change.changedAt()))
                    .orElseGet(() -> new Level(MIN_LEVEL, null));
        } catch (ReadFailed e) {
            log.warn("Congestion level unknown, will try again on the next request: {}", e.getMessage());
            throw new LevelUnknown("level unknown: " + e.getMessage(), e);
        }
        if (current.updatedAt() == null) {
            log.info("Nothing is retained on congestion-topic (never published, or the broker restarted since),"
                    + " so the congestion level starts at {}", current.level());
        } else {
            log.info("Congestion level {} read back from the last change published (at {})",
                    current.level(), current.updatedAt());
        }
        return current;
    }
}
