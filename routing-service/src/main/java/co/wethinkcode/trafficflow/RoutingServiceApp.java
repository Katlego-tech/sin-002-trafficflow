package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.mq.MqConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Optional;

public class RoutingServiceApp {

    static final int PORT = 7023;

    public static void main(String[] args) {
        String intersectionUrl = System.getenv().getOrDefault("INTERSECTION_SERVICE_URL", "http://localhost:7021");
        CongestionView congestion = new CongestionView();
        // Bind the port before subscribing: if it is taken, startup fails here, before a subscriber
        // thread exists to keep a half-started process alive.
        create(new IntersectionClient(intersectionUrl), congestion).start(PORT);

        CongestionSubscriber subscriber = new CongestionSubscriber(MqConfig.BROKER_URL, congestion::apply);
        subscriber.start();
        Runtime.getRuntime().addShutdownHook(new Thread(subscriber::close));
    }

    static Javalin create(IntersectionLookup intersections, CongestionSource congestion) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        // e.g. /travel-time?from=INT-1001&to=INT-1014
        app.get("/travel-time", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                badRequest(ctx, "give both ends, e.g. /travel-time?from=INT-1001&to=INT-1014");
                return;
            }
            // Both ends are validated against the source of truth before anything is estimated.
            Optional<Intersection> start = intersections.find(from);
            if (start.isEmpty()) {
                notFound(ctx, from, "from");
                return;
            }
            Optional<Intersection> end = intersections.find(to);
            if (end.isEmpty()) {
                notFound(ctx, to, "to");
                return;
            }
            if (start.get().id().equals(end.get().id())) {
                badRequest(ctx, "from and to are the same intersection, " + start.get().id());
                return;
            }
            ctx.json(TravelTimeEstimator.estimate(start.get(), end.get(), congestion.current()));
        });

        app.exception(UpstreamUnavailable.class, (e, ctx) -> ctx.status(503).json(Map.of("error", e.getMessage())));

        return app;
    }

    private static void badRequest(Context ctx, String message) {
        ctx.status(400).json(Map.of("error", message));
    }

    private static void notFound(Context ctx, String id, String end) {
        ctx.status(404).json(Map.of("error", "'" + id + "' (" + end + ") is not a known intersection"));
    }
}
