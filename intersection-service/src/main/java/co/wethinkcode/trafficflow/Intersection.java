package co.wethinkcode.trafficflow;

import java.util.List;

/**
 * This service's copy of an ingestion-service record. It is read tolerantly (unknown fields are
 * ignored), so ingestion-service can add fields without breaking this service.
 *
 * @param district   null when the source data doesn't say
 * @param signalType null when the source data doesn't say
 * @param active     null when the source data doesn't say
 * @param notes      how ingestion-service derived the record
 */
public record Intersection(String id, String district, String signalType, Boolean active, List<String> notes) {

    public Intersection {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
