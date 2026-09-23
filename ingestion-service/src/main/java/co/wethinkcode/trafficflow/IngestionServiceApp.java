package co.wethinkcode.trafficflow;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class IngestionServiceApp {

    static final int PORT = 7020;
    private static final String SOURCE = "/intersections-legacy.csv";
    private static final Logger log = LoggerFactory.getLogger(IngestionServiceApp.class);

    public static void main(String[] args) throws IOException {
        create(cleanBundledCsv()).start(PORT);
    }

    /** The export is bundled and never changes while running, so it is cleaned once, at startup. */
    static CleaningReport cleanBundledCsv() throws IOException {
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream(SOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SOURCE + " is not on the classpath");
            }
            CleaningReport report = new IntersectionCsvCleaner().clean(new InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("Cleaned {}: {} rows -> {} intersections, {} rejected",
                    SOURCE, report.rowsRead(), report.intersections().size(), report.rejected().size());
            return report;
        }
    }

    static Javalin create(CleaningReport report) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        // The cleaned records intersection-service loads as its canonical list.
        app.get("/intersections", ctx -> ctx.json(report.intersections()));

        // How the records were derived: the row count, and every rejected row with its reason.
        app.get("/report", ctx -> ctx.json(report));

        return app;
    }
}
