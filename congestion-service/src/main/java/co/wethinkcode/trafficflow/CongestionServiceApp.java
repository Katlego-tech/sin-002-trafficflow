package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.time.Clock;
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
        create(new CongestionLevel(Clock.systemUTC())).start(PORT);
    }

    static Javalin create(CongestionLevel congestion) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/congestion", ctx -> ctx.json(congestion.current()));

        // PUT, not POST: it replaces the state of this one resource, and sending it twice is harmless.
        app.put("/congestion", ctx -> ctx.json(congestion.set(parseLevel(ctx.body()))));

        app.exception(InvalidRequest.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));

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

// MQ TODO: publishes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL (see co.wethinkcode.trafficflow.mq.MqConfig)
