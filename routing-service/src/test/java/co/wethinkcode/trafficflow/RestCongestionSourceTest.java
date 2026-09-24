package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionSource.Reading;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Against a stand-in congestion-service: a real HTTP server on a free port. */
class RestCongestionSourceTest {

    private Javalin congestion;

    @AfterEach
    void stop() {
        congestion.stop();
    }

    private RestCongestionSource sourceFor(int status, String body) {
        congestion = Javalin.create()
                .get("/congestion", ctx -> ctx.status(status).contentType("application/json").result(body))
                .start(0);
        return new RestCongestionSource("http://localhost:" + congestion.port());
    }

    @Test
    void readsTheCurrentLevel() {
        assertEquals(new Reading(4, "2026-09-24T08:00:00Z"),
                sourceFor(200, "{\"level\":4,\"updatedAt\":\"2026-09-24T08:00:00Z\"}").current());
    }

    @Test
    void aLevelThatWasNeverSetHasNoTime() {
        assertEquals(new Reading(0, null), sourceFor(200, "{\"level\":0,\"updatedAt\":null}").current());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"level\":\"4\"}", "{\"level\":9}", "not json"})
    void anUnreadableLevelIsUnavailableNotGuessed(String body) {
        assertThrows(UpstreamUnavailable.class, () -> sourceFor(200, body).current());
    }

    @Test
    void anErrorAnswerIsUnavailable() {
        assertThrows(UpstreamUnavailable.class, () -> sourceFor(500, "{}").current());
    }

    @Test
    void nothingListeningIsUnavailable() {
        RestCongestionSource source = sourceFor(200, "{}");
        congestion.stop();

        assertThrows(UpstreamUnavailable.class, source::current);
    }
}
