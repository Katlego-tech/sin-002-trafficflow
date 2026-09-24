package co.wethinkcode.trafficflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The source of truth for intersection and district names.
 *
 * <p>Loads from ingestion-service on first use and keeps the result. If ingestion-service is
 * down, lookups fail with {@link UpstreamUnavailable} and the next lookup tries again, so it
 * doesn't matter which of the two services is started first.
 */
final class IntersectionDirectory {

    /** A district and the IDs of the intersections in it. */
    record District(String name, List<String> intersectionIds) {
    }

    private record Index(List<Intersection> all, Map<String, Intersection> byId, Map<String, District> byDistrict) {
    }

    private final Supplier<List<Intersection>> source;
    private volatile Index index;

    IntersectionDirectory(Supplier<List<Intersection>> source) {
        this.source = source;
    }

    /** In ingestion-service's order. */
    List<Intersection> all() {
        return index().all();
    }

    /** Ignores case and padding: {@code " int-1005"} finds INT-1005. */
    Optional<Intersection> find(String id) {
        return Optional.ofNullable(index().byId().get(idKey(id)));
    }

    /** In name order. An intersection whose district is unknown is in none of them. */
    List<District> districts() {
        return List.copyOf(index().byDistrict().values());
    }

    /** Ignores case and padding. */
    Optional<District> district(String name) {
        return Optional.ofNullable(index().byDistrict().get(districtKey(name)));
    }

    /** True once the data is loaded, i.e. this service can actually validate a route. */
    boolean isLoaded() {
        return index != null;
    }

    /** Loads if not loaded yet. Never throws: false means ingestion-service is still unavailable. */
    boolean tryLoad() {
        try {
            index();
            return true;
        } catch (UpstreamUnavailable e) {
            return false;
        }
    }

    private Index index() {
        Index current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index == null) {
                index = build(source.get());
            }
            return index;
        }
    }

    private static Index build(List<Intersection> intersections) {
        Map<String, Intersection> byId = new LinkedHashMap<>();
        intersections.forEach(intersection -> byId.putIfAbsent(idKey(intersection.id()), intersection));

        Map<String, District> byDistrict = new TreeMap<>();
        intersections.stream()
                .filter(intersection -> intersection.district() != null)
                .collect(Collectors.groupingBy(intersection -> districtKey(intersection.district()),
                        LinkedHashMap::new, Collectors.toList()))
                .forEach((key, members) -> byDistrict.put(key, new District(
                        members.get(0).district(), members.stream().map(Intersection::id).toList())));

        return new Index(List.copyOf(intersections), byId, byDistrict);
    }

    private static String idKey(String id) {
        return id.strip().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static String districtKey(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
