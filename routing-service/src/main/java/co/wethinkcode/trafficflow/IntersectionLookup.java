package co.wethinkcode.trafficflow;

import java.util.Optional;

/** Validates an intersection ID against the source of truth. Empty if it isn't a real intersection. */
@FunctionalInterface
interface IntersectionLookup {

    /** @throws UpstreamUnavailable if the source of truth can't be asked */
    Optional<Intersection> find(String id);
}
