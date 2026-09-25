package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Fetches the cleaned intersections from ingestion-service ({@code GET /intersections}). */
final class IngestionClient implements Supplier<List<Intersection>> {

    // Tolerant reader: fields this service doesn't know about are ignored, not fatal.
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<Intersection>> INTERSECTIONS = new TypeReference<>() {
    };

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI intersectionsUri;

    IngestionClient(String baseUrl) {
        this.intersectionsUri = URI.create(baseUrl + "/intersections");
    }

    @Override
    public List<Intersection> get() {
        HttpRequest request = HttpRequest.newBuilder(intersectionsUri).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UpstreamUnavailable("ingestion-service answered " + response.statusCode()
                        + " for " + intersectionsUri);
            }
            return JSON.readValue(response.body(), INTERSECTIONS);
        } catch (JsonProcessingException e) {
            throw new UpstreamUnavailable("ingestion-service sent intersections this service could not read", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("ingestion-service is unreachable at " + intersectionsUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted while calling ingestion-service", e);
        }
    }
}
