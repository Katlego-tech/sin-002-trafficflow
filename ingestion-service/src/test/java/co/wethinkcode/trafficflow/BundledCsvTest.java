package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The real {@code intersections-legacy.csv}, end to end. */
class BundledCsvTest {

    private static CleaningReport report;
    private static Map<String, CleanIntersection> byId;

    @BeforeAll
    static void cleanTheRealFile() throws IOException {
        report = IngestionServiceApp.cleanBundledCsv();
        byId = report.intersections().stream()
                .collect(Collectors.toMap(CleanIntersection::id, Function.identity()));
    }

    @Test
    void eighteenRowsAreSeventeenIntersections() {
        assertEquals(18, report.rowsRead());
        assertEquals(List.of(), report.rejected());
        assertEquals(17, report.intersections().size());
    }

    @Test
    void matchesTheReadmesWorkedExample() {
        assertEquals(new CleanIntersection("INT-1001", "Downtown", "4-way", true, List.of()),
                byId.get("INT-1001"));
        assertEquals(new CleanIntersection("INT-1005", "Downtown", "roundabout", true,
                List.of("merged 2 rows with this ID (lines 6, 7)")), byId.get("INT-1005"));
        assertEquals(new CleanIntersection("INT-1007", "Eastside", null, true,
                List.of("signal_type missing in the source (blank)")), byId.get("INT-1007"));
        assertEquals(new CleanIntersection("INT-1015", null, "4-way", true,
                List.of("district missing in the source (blank)")), byId.get("INT-1015"));
    }

    @Test
    void everyValueIsInItsOneNormalForm() {
        assertEquals(Set.of("Downtown", "Midtown", "Uptown", "Eastside", "Westside"),
                values(CleanIntersection::district));
        assertEquals(Set.of("4-way", "pedestrian", "roundabout", "stop-sign"),
                values(CleanIntersection::signalType));
        assertEquals(Set.of(true, false), values(CleanIntersection::active));
    }

    private static <T> Set<T> values(Function<CleanIntersection, T> field) {
        return report.intersections().stream().map(field).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
