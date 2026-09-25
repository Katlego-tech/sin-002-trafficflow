package co.wethinkcode.trafficflow;

/**
 * The event published to {@code congestion-topic} each time the city-wide level changes.
 *
 * @param changedAt ISO-8601 instant of the change
 */
public record CongestionChanged(int level, int previousLevel, String changedAt) {
}
