package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a stand-in intersection-service: a real HTTP server on a free port. */
class IntersectionClientTest {

    private Javalin intersections;

    @AfterEach
    void stop() {
        intersections.stop();
    }

    /** Answers {@code GET /intersections/{id}} with the status and body given, echoing the id it saw. */
    private IntersectionClient clientFor(int status, String body) {
        intersections = Javalin.create()
                .get("/intersections/{id}", ctx -> ctx.status(status).contentType("application/json")
                        .result(body.replace("$id", ctx.pathParam("id"))))
                .start(0);
        return new IntersectionClient("http://localhost:" + intersections.port());
    }

    @Test
    void aKnownIntersectionIsReadIgnoringFieldsItDoesNotUse() {
        IntersectionClient client = clientFor(200, """
                {"id":"INT-1001","district":"Downtown","signalType":"4-way","active":true,"notes":[]}""");

        assertEquals(Optional.of(TravelTimeEstimatorTest.DOWNTOWN_4WAY), client.find("int-1001"));
    }

    @Test
    void notFoundMeansNotAnIntersection() {
        assertEquals(Optional.empty(), clientFor(404, "{\"error\":\"no intersection with ID 'INT-9999'\"}")
                .find("INT-9999"));
    }

    @Test
    void theIdIsSentAsOnePathSegment() {
        IntersectionClient client = clientFor(200, """
                {"id":"$id","district":null,"signalType":null,"active":null}""");

        assertEquals(" int 1001/x", client.find(" int 1001/x").orElseThrow().id());
    }

    @Test
    void anyOtherAnswerMeansRoutesCantBeValidated() {
        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class,
                () -> clientFor(503, "{\"error\":\"ingestion-service is unreachable\"}").find("INT-1001"));

        assertTrue(e.getMessage().startsWith("intersection-service answered 503"), e.getMessage());
    }

    @Test
    void anUnreadableRecordIsUnavailable() {
        assertThrows(UpstreamUnavailable.class, () -> clientFor(200, "not json").find("INT-1001"));
    }

    @Test
    void nothingListeningMeansRoutesCantBeValidated() {
        IntersectionClient client = clientFor(200, "{}");
        intersections.stop();

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> client.find("INT-1001"));
        assertTrue(e.getMessage().endsWith("so routes can't be validated"), e.getMessage());
    }
}
