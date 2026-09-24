package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.IntersectionDirectory.District;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntersectionDirectoryTest {

    static final Intersection DOWNTOWN_4WAY = new Intersection("INT-1001", "Downtown", "4-way", true, List.of());
    static final Intersection MIDTOWN_PEDESTRIAN = new Intersection("INT-1002", "Midtown", "pedestrian", true, List.of());
    static final Intersection DOWNTOWN_STOP = new Intersection("INT-1010", "Downtown", "stop-sign", true, List.of());
    static final Intersection NO_DISTRICT = new Intersection("INT-1015", null, "4-way", true,
            List.of("district missing in the source (blank)"));

    private final AtomicInteger loads = new AtomicInteger();
    private final IntersectionDirectory directory = new IntersectionDirectory(() -> {
        loads.incrementAndGet();
        return List.of(DOWNTOWN_4WAY, MIDTOWN_PEDESTRIAN, DOWNTOWN_STOP, NO_DISTRICT);
    });

    @Test
    void keepsIngestionsOrder() {
        assertEquals(List.of(DOWNTOWN_4WAY, MIDTOWN_PEDESTRIAN, DOWNTOWN_STOP, NO_DISTRICT), directory.all());
    }

    @Test
    void findsAnIntersectionIgnoringCaseAndPadding() {
        assertEquals(DOWNTOWN_4WAY, directory.find(" int-1001 ").orElseThrow());
        assertTrue(directory.find("INT-9999").isEmpty());
    }

    @Test
    void groupsIntersectionsByDistrictInNameOrder() {
        assertEquals(List.of(
                        new District("Downtown", List.of("INT-1001", "INT-1010")),
                        new District("Midtown", List.of("INT-1002"))),
                directory.districts());
    }

    @Test
    void anIntersectionWithNoDistrictIsInNoDistrictButStillValid() {
        assertTrue(directory.districts().stream().noneMatch(d -> d.intersectionIds().contains("INT-1015")));
        assertTrue(directory.find("INT-1015").isPresent());
    }

    @Test
    void findsADistrictIgnoringCaseAndPadding() {
        assertEquals(new District("Downtown", List.of("INT-1001", "INT-1010")),
                directory.district(" DOWNTOWN ").orElseThrow());
        assertTrue(directory.district("Northside").isEmpty());
    }

    @Test
    void loadsOnceAndKeepsTheResult() {
        directory.all();
        directory.find("INT-1001");
        directory.districts();

        assertEquals(1, loads.get());
    }

    @Test
    void aFailedLoadIsReportedAndRetriedNextTime() {
        AtomicInteger calls = new AtomicInteger();
        IntersectionDirectory flaky = new IntersectionDirectory(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new UpstreamUnavailable("ingestion-service is unreachable");
            }
            return List.of(DOWNTOWN_4WAY);
        });

        assertThrows(UpstreamUnavailable.class, flaky::all);
        assertFalse(flaky.isLoaded());

        assertTrue(flaky.tryLoad());
        assertTrue(flaky.isLoaded());
        assertEquals(List.of(DOWNTOWN_4WAY), flaky.all());
    }

    @Test
    void tryLoadNeverThrows() {
        IntersectionDirectory down = new IntersectionDirectory(() -> {
            throw new UpstreamUnavailable("ingestion-service is unreachable");
        });

        assertFalse(down.tryLoad());
    }
}
