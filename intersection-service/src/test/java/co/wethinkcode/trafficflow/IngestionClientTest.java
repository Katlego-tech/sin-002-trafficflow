package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a stand-in ingestion-service: a real HTTP server on a free port. */
class IngestionClientTest {

    private Javalin ingestion;

    @AfterEach
    void stop() {
        ingestion.stop();
    }

    private IngestionClient clientFor(int status, String body) {
        ingestion = Javalin.create()
                .get("/intersections", ctx -> ctx.status(status).contentType("application/json").result(body))
                .start(0);
        return new IngestionClient("http://localhost:" + ingestion.port());
    }

    @Test
    void readsTheCleanedRecordsIncludingExplicitNulls() {
        IngestionClient client = clientFor(200, """
                [{"id":"INT-1001","district":"Downtown","signalType":"4-way","active":true,"notes":[]},
                 {"id":"INT-1015","district":null,"signalType":"4-way","active":true,
                  "notes":["district missing in the source (blank)"]}]
                """);

        assertEquals(List.of(IntersectionDirectoryTest.DOWNTOWN_4WAY, IntersectionDirectoryTest.NO_DISTRICT),
                client.get());
    }

    @Test
    void fieldsItDoesNotKnowAreIgnoredSoIngestionCanAddThem() {
        IngestionClient client = clientFor(200, """
                [{"id":"INT-1001","district":"Downtown","signalType":"4-way","active":true,"notes":[],
                  "lanes":4}]
                """);

        assertEquals(List.of(IntersectionDirectoryTest.DOWNTOWN_4WAY), client.get());
    }

    @Test
    void anErrorStatusIsUnavailable() {
        IngestionClient client = clientFor(500, "{}");

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, client::get);
        assertTrue(e.getMessage().contains("answered 500"), e.getMessage());
    }

    @Test
    void anUnreadableBodyIsUnavailable() {
        IngestionClient client = clientFor(200, "not json");

        assertThrows(UpstreamUnavailable.class, client::get);
    }

    @Test
    void nothingListeningIsUnavailable() {
        IngestionClient client = clientFor(200, "[]");
        ingestion.stop();

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, client::get);
        assertTrue(e.getMessage().startsWith("ingestion-service is unreachable"), e.getMessage());
    }
}
