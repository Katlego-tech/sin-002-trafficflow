package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionSource.Reading;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** GET /travel-time over HTTP, with intersection-service and the congestion level replaced by lambdas. */
class RoutingServiceAppTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Intersection> KNOWN = Map.of(
            "INT-1001", TravelTimeEstimatorTest.DOWNTOWN_4WAY,
            "INT-1014", TravelTimeEstimatorTest.UPTOWN_PEDESTRIAN);
    /** Behaves like intersection-service: ignores case and padding. */
    private static final IntersectionLookup LOOKUP =
            id -> Optional.ofNullable(KNOWN.get(id.strip().toUpperCase(Locale.ROOT)));
    private static final CongestionSource LEVEL_4 = () -> new Reading(4, "2026-09-24T08:00:00Z");

    private Javalin app;

    @AfterEach
    void stop() {
        app.stop();
    }

    private JsonNode get(IntersectionLookup intersections, CongestionSource congestion, String query,
                         int expectedStatus) throws Exception {
        app = RoutingServiceApp.create(intersections, congestion).start(0);
        URI uri = URI.create("http://localhost:" + app.port() + "/travel-time" + query);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        return JSON.readTree(response.body());
    }

    private static String error(JsonNode body) {
        return body.get("error").asText();
    }

    @Test
    void estimatesBetweenTwoValidIntersections() throws Exception {
        JsonNode estimate = get(LOOKUP, LEVEL_4, "?from=int-1001&to=INT-1014", 200);

        assertEquals("INT-1001", estimate.get("from").get("id").asText());
        assertEquals("INT-1014", estimate.get("to").get("id").asText());
        assertEquals(4, estimate.get("congestionLevel").asInt());
        assertEquals("2026-09-24T08:00:00Z", estimate.get("congestionAsOf").asText());
        assertEquals(14.0, estimate.get("baseMinutes").asDouble());
        assertEquals(2.0, estimate.get("congestionMultiplier").asDouble());
        assertEquals(28.0, estimate.get("estimatedMinutes").asDouble());
        assertEquals(0, estimate.get("warnings").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?from=INT-1001", "?to=INT-1014", "?from=&to=INT-1014", "?from=%20&to=INT-1014"})
    void aMissingEndIs400(String query) throws Exception {
        assertEquals("give both ends, e.g. /travel-time?from=INT-1001&to=INT-1014",
                error(get(LOOKUP, LEVEL_4, query, 400)));
    }

    @Test
    void theSameIntersectionAtBothEndsIs400() throws Exception {
        assertEquals("from and to are the same intersection, INT-1001",
                error(get(LOOKUP, LEVEL_4, "?from=INT-1001&to=%20int-1001", 400)));
    }

    @Test
    void anUnknownEndIs404AndSaysWhichEnd() throws Exception {
        assertEquals("'INT-9999' (from) is not a known intersection",
                error(get(LOOKUP, LEVEL_4, "?from=INT-9999&to=INT-1014", 404)));
        stop();
        assertEquals("'INT-9999' (to) is not a known intersection",
                error(get(LOOKUP, LEVEL_4, "?from=INT-1001&to=INT-9999", 404)));
    }

    @Test
    void congestionIsOnlyAskedForOnceBothEndsAreValid() throws Exception {
        AtomicInteger asked = new AtomicInteger();

        get(LOOKUP, () -> {
            asked.incrementAndGet();
            return LEVEL_4.current();
        }, "?from=INT-1001&to=INT-9999", 404);

        assertEquals(0, asked.get());
    }

    @Test
    void intersectionServiceDownIs503BecauseRoutesCantBeValidated() throws Exception {
        IntersectionLookup down = id -> {
            throw new UpstreamUnavailable("intersection-service is unreachable at http://localhost:7021, "
                    + "so routes can't be validated");
        };

        assertEquals("intersection-service is unreachable at http://localhost:7021, so routes can't be validated",
                error(get(down, LEVEL_4, "?from=INT-1001&to=INT-1014", 503)));
    }

    @Test
    void beforeAnyLevelHasArrivedTheEstimateSaysSo() throws Exception {
        JsonNode estimate = get(LOOKUP, () -> Reading.UNKNOWN, "?from=INT-1001&to=INT-1014", 200);

        assertEquals(true, estimate.get("congestionLevel").isNull());
        assertEquals(14.0, estimate.get("estimatedMinutes").asDouble());
        assertEquals("no congestion level has been received yet, so clear roads (level 0) are assumed",
                estimate.get("warnings").get(0).asText());
    }

    @Test
    void warningsTravelWithTheEstimate() throws Exception {
        IntersectionLookup withGaps = id -> Optional.of(id.equals("INT-1015")
                ? new Intersection("INT-1015", null, "4-way", true)
                : TravelTimeEstimatorTest.DOWNTOWN_4WAY);

        JsonNode estimate = get(withGaps, LEVEL_4, "?from=INT-1001&to=INT-1015", 200);

        assertEquals(List.of("INT-1015 has no district in the source data, so the longest crossing is assumed"),
                JSON.convertValue(estimate.get("warnings"), List.class));
    }
}
