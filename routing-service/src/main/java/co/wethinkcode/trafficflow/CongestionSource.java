package co.wethinkcode.trafficflow;

/** Where routing-service gets the city-wide congestion level from. */
@FunctionalInterface
interface CongestionSource {

    /**
     * @param level 0 (clear) to 8 (gridlock)
     * @param asOf  when the level was set, or null if it never has been
     */
    record Reading(int level, String asOf) {
    }

    Reading current();
}
