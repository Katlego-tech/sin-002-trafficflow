package co.wethinkcode.trafficflow;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The four endpoints over HTTP, with ingestion-service replaced by a lambda. */
class IntersectionServiceAppTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Javalin app;

    @AfterEach
    void stop() {
        app.stop();
    }

    private static IntersectionDirectory loaded() {
        return new IntersectionDirectory(() -> List.of(
                IntersectionDirectoryTest.DOWNTOWN_4WAY,
                IntersectionDirectoryTest.DOWNTOWN_STOP,
                IntersectionDirectoryTest.NO_DISTRICT));
    }

    private static IntersectionDirectory ingestionDown() {
        return new IntersectionDirectory(() -> {
            throw new UpstreamUnavailable("ingestion-service is unreachable at http://localhost:7020/intersections");
        });
    }

    /** GETs {@code path} and checks the status; every answer, errors included, is JSON. */
    private JsonNode get(IntersectionDirectory directory, String path, int expectedStatus) throws Exception {
        app = IntersectionServiceApp.create(directory).start(0);
        URI uri = URI.create("http://localhost:" + app.port() + path);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        return JSON.readTree(response.body());
    }

    @Test
    void listsEveryIntersection() throws Exception {
        JsonNode all = get(loaded(), "/intersections", 200);

        assertEquals(3, all.size());
        assertEquals("INT-1001", all.get(0).get("id").asText());
    }

    @Test
    void aKnownIntersectionIs200WithItsRecord() throws Exception {
        JsonNode intersection = get(loaded(), "/intersections/int-1015", 200);

        assertEquals("INT-1015", intersection.get("id").asText());
        assertEquals(true, intersection.get("district").isNull());
        assertEquals("district missing in the source (blank)", intersection.get("notes").get(0).asText());
    }

    @Test
    void anUnknownIntersectionIs404AndSaysWhich() throws Exception {
        JsonNode error = get(loaded(), "/intersections/INT-9999", 404);

        assertEquals("no intersection with ID 'INT-9999'", error.get("error").asText());
    }

    @Test
    void listsDistrictsWithTheirIntersections() throws Exception {
        JsonNode districts = get(loaded(), "/districts", 200);

        assertEquals(1, districts.size());
        assertEquals("Downtown", districts.get(0).get("name").asText());
        assertEquals("[\"INT-1001\",\"INT-1010\"]", districts.get(0).get("intersectionIds").toString());
    }

    @Test
    void aKnownDistrictIs200() throws Exception {
        assertEquals("Downtown", get(loaded(), "/districts/downtown", 200).get("name").asText());
    }

    @Test
    void anUnknownDistrictIs404AndSaysWhich() throws Exception {
        JsonNode error = get(loaded(), "/districts/Northside", 404);

        assertEquals("no district called 'Northside'", error.get("error").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/intersections", "/intersections/INT-1001", "/districts", "/districts/Downtown"})
    void withoutIngestionEveryLookupIs503AndSaysWhy(String path) throws Exception {
        JsonNode error = get(ingestionDown(), path, 503);

        assertEquals("ingestion-service is unreachable at http://localhost:7020/intersections",
                error.get("error").asText());
    }
}
