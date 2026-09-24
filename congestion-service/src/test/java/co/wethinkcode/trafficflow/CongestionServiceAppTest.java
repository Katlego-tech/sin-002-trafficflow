package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET and PUT /congestion over HTTP. */
class CongestionServiceAppTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CongestionChanged> published = new ArrayList<>();
    private Javalin app;

    @BeforeEach
    void start() {
        start(published::add);
    }

    private void start(CongestionPublisher publisher) {
        app = CongestionServiceApp.create(new CongestionLevel(publisher, () -> Instant.parse("2026-09-24T08:00:00Z")))
                .start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = HTTP.send(
                request.uri(URI.create("http://localhost:" + app.port() + "/congestion")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        return response;
    }

    private JsonNode get() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder().GET());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> put(String body) throws Exception {
        return send(HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    @Test
    void theLevelStartsAtZeroAndNeverSet() throws Exception {
        assertEquals("{\"level\":0,\"updatedAt\":null}", get().toString());
    }

    @Test
    void putSetsTheLevelAndAnswersWithTheNewState() throws Exception {
        HttpResponse<String> response = put("{\"level\": 4}");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("{\"level\":4,\"updatedAt\":\"2026-09-24T08:00:00Z\"}", JSON.readTree(response.body()).toString());
        assertEquals(4, get().get("level").asInt());
    }

    @Test
    void aNewLevelIsPublished() throws Exception {
        put("{\"level\": 4}");

        assertEquals(List.of(new CongestionChanged(4, 0, "2026-09-24T08:00:00Z")), published);
    }

    @Test
    void putIsIdempotent() throws Exception {
        String first = put("{\"level\": 5}").body();

        assertEquals(first, put("{\"level\": 5}").body());
        assertEquals(1, published.size(), "the same level is not published twice");
    }

    @Test
    void ifTheBrokerIsDownTheLevelIsNotChangedAndItSaysSo() throws Exception {
        stop();
        start(event -> {
            throw new CongestionPublisher.PublishFailed("could not publish to congestion-topic: connection refused",
                    null);
        });

        HttpResponse<String> response = put("{\"level\": 4}");

        assertEquals(503, response.statusCode(), response.body());
        assertEquals("level not changed: could not publish to congestion-topic: connection refused",
                JSON.readTree(response.body()).get("error").asText());
        assertEquals(0, get().get("level").asInt());
    }

    @Test
    void fieldsItDoesNotKnowAreIgnored() throws Exception {
        assertEquals(200, put("{\"level\": 2, \"reason\": \"match day\"}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"level\": 9}", "{\"level\": -1}", "{\"level\": 1.5}", "{\"level\": \"3\"}",
            "{\"level\": null}", "{\"level\": 99999999999}", "{}", "[4]", "4", "not json", ""})
    void anInvalidBodyIs400AndTheLevelStays(String body) throws Exception {
        HttpResponse<String> response = put(body);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).get("error").asText().startsWith("body must be JSON like"),
                response.body());
        assertEquals(0, get().get("level").asInt());
        assertEquals(List.of(), published);
    }

    @Test
    void anOutOfRangeLevelSaysWhatItGot() throws Exception {
        assertEquals("body must be JSON like {\"level\": 3}, with a whole number from 0 to 8; got 9",
                JSON.readTree(put("{\"level\": 9}").body()).get("error").asText());
    }
}
