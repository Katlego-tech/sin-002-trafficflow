package co.wethinkcode.trafficflow;

import java.util.List;

/**
 * One intersection after cleaning. A field the source didn't give (or contradicted itself on)
 * is an explicit null, so downstream services can see it's missing rather than guess.
 *
 * @param signalType lower case: {@code 4-way}, {@code pedestrian}, {@code roundabout} or {@code stop-sign}
 * @param notes      everything a consumer should know about how this record was derived
 */
public record CleanIntersection(
        String id,
        String district,
        String signalType,
        Boolean active,
        List<String> notes) {
}
