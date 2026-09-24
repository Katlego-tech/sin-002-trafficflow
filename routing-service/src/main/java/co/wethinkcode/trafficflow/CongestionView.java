package co.wethinkcode.trafficflow;

import java.time.Instant;
import java.util.Objects;

/**
 * routing-service's own copy of the congestion level, kept current by the changes congestion-service
 * publishes to {@code congestion-topic}. An estimate reads this copy and calls no other service
 * for congestion.
 */
final class CongestionView implements CongestionSource {

    /**
     * This service's copy of the change congestion-service publishes; its other fields are ignored.
     * A change that makes no sense can't be constructed, so it can't be applied.
     */
    record CongestionChanged(int level, String changedAt) {

        CongestionChanged {
            if (level < 0 || level > 8) {
                throw new IllegalArgumentException("level " + level + " is not from 0 to 8");
            }
            Instant.parse(Objects.requireNonNull(changedAt, "changedAt is missing"));
        }
    }

    private Reading current = Reading.UNKNOWN;

    /**
     * Ignores a change older than the one already held, such as a retained copy the broker hands
     * over again when the subscription reconnects.
     */
    synchronized void apply(CongestionChanged change) {
        if (current.known() && Instant.parse(change.changedAt()).isBefore(Instant.parse(current.asOf()))) {
            return;
        }
        current = new Reading(change.level(), change.changedAt());
    }

    @Override
    public synchronized Reading current() {
        return current;
    }
}
