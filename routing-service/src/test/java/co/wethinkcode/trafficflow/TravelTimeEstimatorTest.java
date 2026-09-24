package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionSource.Reading;
import co.wethinkcode.trafficflow.TravelTimeEstimator.Estimate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TravelTimeEstimatorTest {

    static final Intersection DOWNTOWN_4WAY = new Intersection("INT-1001", "Downtown", "4-way", true);
    static final Intersection UPTOWN_PEDESTRIAN = new Intersection("INT-1014", "Uptown", "pedestrian", true);

    private static Reading level(int level) {
        return new Reading(level, "2026-09-24T08:00:00Z");
    }

    private static Estimate estimate(Intersection to, int level) {
        return TravelTimeEstimator.estimate(DOWNTOWN_4WAY, to, level(level));
    }

    @Test
    void clearRoadsAreTheCrossingPlusTheSignalsAtBothEnds() {
        Estimate estimate = estimate(UPTOWN_PEDESTRIAN, 0);

        assertEquals(new Estimate(DOWNTOWN_4WAY, UPTOWN_PEDESTRIAN, 0, "2026-09-24T08:00:00Z",
                12 + 1.0 + 1.0, 1.0, 14.0, List.of()), estimate);
    }

    @Test
    void eachCongestionLevelAddsAQuarterOfTheTrip() {
        assertEquals(14.0, estimate(UPTOWN_PEDESTRIAN, 0).estimatedMinutes());
        assertEquals(2.0, estimate(UPTOWN_PEDESTRIAN, 4).congestionMultiplier());
        assertEquals(28.0, estimate(UPTOWN_PEDESTRIAN, 4).estimatedMinutes());
        assertEquals(42.0, estimate(UPTOWN_PEDESTRIAN, 8).estimatedMinutes());
    }

    @Test
    void theSignalTypeChangesTheDelay() {
        Intersection roundabout = new Intersection("INT-1005", "Downtown", "roundabout", true);
        Intersection stopSign = new Intersection("INT-1010", "Downtown", "stop-sign", true);

        assertEquals(4 + 1.0 + 0.5, estimate(roundabout, 0).baseMinutes());
        assertEquals(4 + 1.0 + 1.5, estimate(stopSign, 0).baseMinutes());
    }

    @Test
    void anIntersectionWithNoDistrictGetsTheLongestCrossingAndAWarning() {
        Intersection noDistrict = new Intersection("INT-1015", null, "4-way", true);

        Estimate estimate = estimate(noDistrict, 0);

        assertEquals(DistrictMap.longestCrossing() + 1.0 + 1.0, estimate.baseMinutes());
        assertEquals(List.of("INT-1015 has no district in the source data, so the longest crossing is assumed"),
                estimate.warnings());
    }

    @Test
    void aDistrictOffTheMapGetsTheLongestCrossingAndAWarning() {
        Intersection offMap = new Intersection("INT-2001", "Harbour", "4-way", true);

        assertEquals(List.of("INT-2001 is in 'Harbour', which isn't on the district map, so the longest crossing"
                + " is assumed"), estimate(offMap, 0).warnings());
    }

    @Test
    void inactiveSignalsCostTheMostWhateverTheirType() {
        Intersection inactive = new Intersection("INT-1012", "Eastside", "roundabout", false);

        Estimate estimate = estimate(inactive, 0);

        assertEquals(8 + 1.0 + TravelTimeEstimator.INACTIVE_SIGNAL_DELAY, estimate.baseMinutes());
        assertEquals(List.of("the signals at INT-1012 are inactive, so it is treated as an all-way stop"),
                estimate.warnings());
    }

    @Test
    void anUnknownSignalTypeGetsATypicalDelayAndAWarning() {
        Intersection unknownType = new Intersection("INT-1004", "Uptown", null, true);

        Estimate estimate = estimate(unknownType, 0);

        assertEquals(12 + 1.0 + TravelTimeEstimator.TYPICAL_SIGNAL_DELAY, estimate.baseMinutes());
        assertEquals(List.of("the signal type at INT-1004 is unknown, so a typical delay is assumed"),
                estimate.warnings());
    }

    @Test
    void anUnrecognisedSignalTypeGetsATypicalDelayAndAWarning() {
        Intersection flashing = new Intersection("INT-1041", "Uptown", "flashing amber", true);

        assertEquals(List.of("the signal type 'flashing amber' at INT-1041 has no known delay, so a typical delay"
                + " is assumed"), estimate(flashing, 0).warnings());
    }

    @Test
    void notKnowingWhetherTheSignalsWorkIsFlaggedButNotPenalised() {
        Intersection mightWork = new Intersection("INT-1013", "Westside", "4-way", null);

        Estimate estimate = estimate(mightWork, 0);

        assertEquals(8 + 1.0 + 1.0, estimate.baseMinutes());
        assertEquals(List.of("the source data doesn't say whether the signals at INT-1013 work"), estimate.warnings());
    }

    @Test
    void int1013HasEveryGapAtOnce() {
        Intersection int1013 = new Intersection("INT-1013", "Westside", null, null);

        assertEquals(List.of(
                        "the source data doesn't say whether the signals at INT-1013 work",
                        "the signal type at INT-1013 is unknown, so a typical delay is assumed"),
                estimate(int1013, 0).warnings());
    }
}
