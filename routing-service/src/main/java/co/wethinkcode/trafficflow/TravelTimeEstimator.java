package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionSource.Reading;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estimated travel time between two intersections that intersection-service has validated:
 * <pre>
 *   (district crossing + signal delay at each end) x (1 + 0.25 x congestion level)
 * </pre>
 * so level 0 is free-flowing and level 8 triples the trip. Gaps in the source data still give an
 * estimate, but every assumption made to fill one is listed in the estimate's warnings.
 */
final class TravelTimeEstimator {

    /**
     * @param baseMinutes          the trip on clear roads
     * @param congestionMultiplier what the congestion level multiplies it by
     * @param warnings             every assumption made because the source data has a gap
     */
    record Estimate(
            Intersection from,
            Intersection to,
            int congestionLevel,
            String congestionAsOf,
            double baseMinutes,
            double congestionMultiplier,
            double estimatedMinutes,
            List<String> warnings) {
    }

    static final double PER_CONGESTION_LEVEL = 0.25;
    static final double TYPICAL_SIGNAL_DELAY = 1.0;
    static final double INACTIVE_SIGNAL_DELAY = 2.0;
    private static final Map<String, Double> SIGNAL_DELAY = Map.of(
            "roundabout", 0.5,
            "4-way", 1.0,
            "pedestrian", 1.0,
            "stop-sign", 1.5);

    private TravelTimeEstimator() {
    }

    static Estimate estimate(Intersection from, Intersection to, Reading congestion) {
        List<String> warnings = new ArrayList<>();
        Optional<String> fromDistrict = onMap(from, warnings);
        Optional<String> toDistrict = onMap(to, warnings);
        int crossing = fromDistrict.isPresent() && toDistrict.isPresent()
                ? DistrictMap.minutes(fromDistrict.get(), toDistrict.get())
                : DistrictMap.longestCrossing();

        double base = crossing + signalDelay(from, warnings) + signalDelay(to, warnings);
        double multiplier = 1 + PER_CONGESTION_LEVEL * congestion.level();
        return new Estimate(from, to, congestion.level(), congestion.asOf(),
                round(base), multiplier, round(base * multiplier), warnings);
    }

    private static Optional<String> onMap(Intersection intersection, List<String> warnings) {
        Optional<String> district = DistrictMap.find(intersection.district());
        if (district.isEmpty()) {
            warnings.add(intersection.id() + (intersection.district() == null
                    ? " has no district in the source data"
                    : " is in '" + intersection.district() + "', which isn't on the district map")
                    + ", so the longest crossing is assumed");
        }
        return district;
    }

    private static double signalDelay(Intersection intersection, List<String> warnings) {
        if (Boolean.FALSE.equals(intersection.active())) {
            warnings.add("the signals at " + intersection.id() + " are inactive, so it is treated as an all-way stop");
            return INACTIVE_SIGNAL_DELAY;
        }
        if (intersection.active() == null) {
            warnings.add("the source data doesn't say whether the signals at " + intersection.id() + " work");
        }
        String type = intersection.signalType();
        Double delay = type == null ? null : SIGNAL_DELAY.get(type);
        if (delay == null) {
            warnings.add("the signal type " + (type == null
                    ? "at " + intersection.id() + " is unknown"
                    : "'" + type + "' at " + intersection.id() + " has no known delay")
                    + ", so a typical delay is assumed");
            return TYPICAL_SIGNAL_DELAY;
        }
        return delay;
    }

    private static double round(double minutes) {
        return Math.round(minutes * 10) / 10.0;
    }
}
