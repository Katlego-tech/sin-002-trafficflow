package co.wethinkcode.trafficflow;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Driving minutes between the city's districts.
 *
 * <p>The legacy data names districts but has no roads, so these times are an assumption, kept
 * here in one place. Replace this class with real road data and nothing else has to change.
 */
final class DistrictMap {

    static final int WITHIN_A_DISTRICT = 4;

    /** Keyed by the unordered pair of districts: a crossing takes as long either way. */
    private static final Map<Set<String>, Integer> CROSSING_MINUTES = Map.ofEntries(
            crossing("Downtown", "Midtown", 6),
            crossing("Downtown", "Uptown", 12),
            crossing("Downtown", "Eastside", 8),
            crossing("Downtown", "Westside", 8),
            crossing("Midtown", "Uptown", 6),
            crossing("Midtown", "Eastside", 9),
            crossing("Midtown", "Westside", 9),
            crossing("Uptown", "Eastside", 12),
            crossing("Uptown", "Westside", 15),
            crossing("Eastside", "Westside", 16));

    private static final Set<String> DISTRICTS = CROSSING_MINUTES.keySet().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

    private DistrictMap() {
    }

    private static Map.Entry<Set<String>, Integer> crossing(String a, String b, int minutes) {
        return Map.entry(Set.of(a, b), minutes);
    }

    /** The district's name as the map spells it, if the map has it. */
    static Optional<String> find(String district) {
        if (district == null) {
            return Optional.empty();
        }
        return DISTRICTS.stream().filter(d -> d.equalsIgnoreCase(district.strip())).findFirst();
    }

    /** Both districts must be spelled as {@link #find} returns them. */
    static int minutes(String from, String to) {
        return from.equals(to) ? WITHIN_A_DISTRICT : CROSSING_MINUTES.get(Set.of(from, to));
    }

    /** The slowest crossing in the city: the pessimistic guess when a district is unknown. */
    static int longestCrossing() {
        return Collections.max(CROSSING_MINUTES.values());
    }
}
