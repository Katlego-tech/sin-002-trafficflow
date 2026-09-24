package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cleaned data as other services see it: JSON over HTTP. */
class IngestionServiceAppTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Javalin app;

    @BeforeAll
    static void start() throws Exception {
        app = IngestionServiceApp.create(IngestionServiceApp.cleanBundledCsv()).start(0);
    }

    @AfterAll
    static void stop() {
        app.stop();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        URI uri = URI.create("http://localhost:" + app.port() + path);
        return HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        return new ObjectMapper().readTree(response.body());
    }

    @Test
    void servesTheCleanedIntersectionsAsAJsonArray() throws Exception {
        JsonNode intersections = json(get("/intersections"));

        assertEquals(17, intersections.size());
        JsonNode first = intersections.get(0);
        assertEquals("INT-1001", first.get("id").asText());
        assertEquals("Downtown", first.get("district").asText());
        assertEquals("4-way", first.get("signalType").asText());
        assertTrue(first.get("active").asBoolean());
    }

    @Test
    void aMissingValueIsAnExplicitJsonNullNotAnAbsentField() throws Exception {
        JsonNode int1007 = json(get("/intersections")).get(6);

        assertEquals("INT-1007", int1007.get("id").asText());
        assertTrue(int1007.has("signalType"));
        assertTrue(int1007.get("signalType").isNull());
    }

    @Test
    void theReportAccountsForEveryRow() throws Exception {
        JsonNode report = json(get("/report"));

        assertEquals(18, report.get("rowsRead").asInt());
        assertEquals(17, report.get("intersections").size());
        assertEquals(0, report.get("rejected").size());
    }
}
