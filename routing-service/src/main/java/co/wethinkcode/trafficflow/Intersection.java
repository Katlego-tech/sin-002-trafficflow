package co.wethinkcode.trafficflow;

/**
 * The part of intersection-service's record this service uses. It is read tolerantly, so
 * intersection-service's other fields are ignored.
 *
 * @param district   null when the source data doesn't say
 * @param signalType null when the source data doesn't say
 * @param active     null when the source data doesn't say
 */
public record Intersection(String id, String district, String signalType, Boolean active) {
}
