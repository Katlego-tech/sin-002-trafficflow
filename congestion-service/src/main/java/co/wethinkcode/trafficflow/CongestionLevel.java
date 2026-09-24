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

    private final InstantSource clock;
    private volatile Level current = new Level(MIN_LEVEL, null);

    CongestionLevel(InstantSource clock) {
        this.clock = clock;
    }

    Level current() {
        return current;
    }

    /** Setting the level it already has changes nothing, not even {@code updatedAt}. */
    synchronized Level set(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be from " + MIN_LEVEL + " to " + MAX_LEVEL + ", got " + level);
        }
        if (level != current.level()) {
            current = new Level(level, clock.instant().toString());
        }
        return current;
    }
}
