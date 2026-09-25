package co.wethinkcode.trafficflow;

/** Where routing-service gets the city-wide congestion level from. */
@FunctionalInterface
interface CongestionSource {

    /**
     * @param level 0 (clear) to 8 (gridlock), or null if no level has been received yet
     * @param asOf  when the level was set, or null if it is unknown
     */
    record Reading(Integer level, String asOf) {

        static final Reading UNKNOWN = new Reading(null, null);

        boolean known() {
            return level != null;
        }
    }

    Reading current();
}
