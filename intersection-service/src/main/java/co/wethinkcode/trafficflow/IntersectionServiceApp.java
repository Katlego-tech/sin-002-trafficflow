package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class IntersectionServiceApp {

    static final int PORT = 7021;
    private static final Logger log = LoggerFactory.getLogger(IntersectionServiceApp.class);

    public static void main(String[] args) {
        String ingestionUrl = System.getenv().getOrDefault("INGESTION_URL", "http://localhost:7020");
        IntersectionDirectory directory = new IntersectionDirectory(new IngestionClient(ingestionUrl));
        if (directory.tryLoad()) {
            log.info("Loaded {} intersections from {}", directory.all().size(), ingestionUrl);
        } else {
            log.warn("ingestion-service is unavailable at {}; lookups answer 503 and retry until it is up", ingestionUrl);
        }
        create(directory).start(PORT);
    }

    static Javalin create(IntersectionDirectory directory) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/intersections", ctx -> ctx.json(directory.all()));

        // routing-service validates a route's endpoints here: 404 means "not a real intersection".
        app.get("/intersections/{id}", ctx -> {
            String id = ctx.pathParam("id");
            directory.find(id).ifPresentOrElse(
                    ctx::json,
                    () -> ctx.status(404).json(Map.of("error", "no intersection with ID '" + id + "'")));
        });

        app.get("/districts", ctx -> ctx.json(directory.districts()));

        app.get("/districts/{name}", ctx -> {
            String name = ctx.pathParam("name");
            directory.district(name).ifPresentOrElse(
                    ctx::json,
                    () -> ctx.status(404).json(Map.of("error", "no district called '" + name + "'")));
        });

        // Without ingestion-service's data nothing can be validated, so say so rather than answer 404.
        app.exception(UpstreamUnavailable.class,
                (e, ctx) -> ctx.status(503).json(Map.of("error", e.getMessage())));

        return app;
    }
}

// MQ TODO: publishes a periodic heartbeat to ActiveMQ queue MqConfig.HEARTBEAT_QUEUE at
// MqConfig.BROKER_URL (see co.wethinkcode.trafficflow.mq.MqConfig), consumed by intersection-watchdog.
