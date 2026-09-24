package co.wethinkcode.trafficflow;

import java.time.InstantSource;

/**
 * The city-wide Congestion Level: 0 (clear) to 8 (gridlock). Starts at 0.
 */
final class CongestionLevel {

    static final int MIN_LEVEL = 0;
    static final int MAX_LEVEL = 8;

    /** The current level, and when it last changed: null if it never has. */
    record Level(int level, String updatedAt) {
    }

    private final CongestionPublisher publisher;
    private final InstantSource clock;
    private volatile Level current = new Level(MIN_LEVEL, null);

    CongestionLevel(CongestionPublisher publisher, InstantSource clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    Level current() {
        return current;
    }

    /**
     * Publishes the change first and records it second: if it can't be published, the level stays
     * as it was, so this service never holds a level its subscribers weren't told about. Setting
     * the level it already has changes and publishes nothing, not even {@code updatedAt}.
     *
     * @throws CongestionPublisher.PublishFailed if the change could not be published
     */
    synchronized Level set(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be from " + MIN_LEVEL + " to " + MAX_LEVEL + ", got " + level);
        }
        if (level == current.level()) {
            return current;
        }
        String now = clock.instant().toString();
        publisher.publish(new CongestionChanged(level, current.level(), now));
        current = new Level(level, now);
        return current;
    }
}
