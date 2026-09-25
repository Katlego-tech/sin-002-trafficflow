package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionLevel.LevelUnknown;
import co.wethinkcode.trafficflow.CongestionPublisher.PublishFailed;
import co.wethinkcode.trafficflow.mq.MqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

public class CongestionServiceApp {

    static final int PORT = 7022;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXPECTED_BODY = "body must be JSON like {\"level\": 3}, with a whole number from "
            + CongestionLevel.MIN_LEVEL + " to " + CongestionLevel.MAX_LEVEL;

    /** The request body was not a valid level. */
    static class InvalidRequest extends RuntimeException {
        InvalidRequest(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        JmsCongestionPublisher publisher = new JmsCongestionPublisher(MqConfig.BROKER_URL);
        Runtime.getRuntime().addShutdownHook(new Thread(publisher::close));
        CongestionLevel congestion = new CongestionLevel(publisher,
                new RetainedLevelReader(MqConfig.BROKER_URL, Duration.ofSeconds(2)), Clock.systemUTC());
        try {
            congestion.current(); // read the level back now, so the log says where it came from
        } catch (LevelUnknown e) {
            // Logged already; the next request tries again, so the start order doesn't matter.
        }
        create(congestion).start(PORT);
    }

    static Javalin create(CongestionLevel congestion) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/congestion", ctx -> ctx.json(congestion.current()));

        // PUT, not POST: it replaces the state of this one resource, and sending it twice is harmless.
        // A change is published to congestion-topic before it is recorded.
        app.put("/congestion", ctx -> ctx.json(congestion.set(parseLevel(ctx.body()))));

        app.exception(InvalidRequest.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(PublishFailed.class,
                (e, ctx) -> ctx.status(503).json(Map.of("error", "level not changed: " + e.getMessage())));
        app.exception(LevelUnknown.class, (e, ctx) -> ctx.status(503).json(Map.of("error",
                (ctx.method() == HandlerType.PUT ? "level not changed: " : "") + e.getMessage())));

        return app;
    }

    /** The {@code level} from a body like {@code {"level": 3}}: strictly a whole number in range, never coerced. */
    private static int parseLevel(String body) {
        JsonNode level;
        try {
            level = JSON.readTree(body).get("level");
        } catch (JsonProcessingException e) {
            throw new InvalidRequest(EXPECTED_BODY);
        }
        if (level == null || !level.isIntegralNumber() || !level.canConvertToInt()) {
            throw new InvalidRequest(EXPECTED_BODY);
        }
        int value = level.intValue();
        if (value < CongestionLevel.MIN_LEVEL || value > CongestionLevel.MAX_LEVEL) {
            throw new InvalidRequest(EXPECTED_BODY + "; got " + value);
        }
        return value;
    }
}
