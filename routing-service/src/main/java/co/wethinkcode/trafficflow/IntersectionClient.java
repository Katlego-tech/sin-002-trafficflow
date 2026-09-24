package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** Looks intersections up in intersection-service ({@code GET /intersections/{id}}). */
final class IntersectionClient implements IntersectionLookup {

    // Tolerant reader: fields this service doesn't use are ignored, not fatal.
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;

    IntersectionClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<Intersection> find(String id) {
        // Encoded as a single path segment, so whatever the caller typed can't change the path.
        String segment = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(baseUrl + "/intersections/" + segment);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> Optional.of(JSON.readValue(response.body(), Intersection.class));
                case 404 -> Optional.empty();
                default -> throw new UpstreamUnavailable("intersection-service answered " + response.statusCode()
                        + " for " + uri + ", so routes can't be validated");
            };
        } catch (JsonProcessingException e) {
            throw new UpstreamUnavailable("intersection-service sent an intersection this service could not read", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("intersection-service is unreachable at " + baseUrl
                    + ", so routes can't be validated", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted while calling intersection-service", e);
        }
    }
}
