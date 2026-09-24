package co.wethinkcode.trafficflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks congestion-service for the level ({@code GET /congestion}) on every estimate, so no
 * estimate can be made while congestion-service is down.
 */
final class RestCongestionSource implements CongestionSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI uri;

    RestCongestionSource(String baseUrl) {
        this.uri = URI.create(baseUrl + "/congestion");
    }

    @Override
    public Reading current() {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UpstreamUnavailable("congestion-service answered " + response.statusCode() + " for " + uri);
            }
            return read(JSON.readTree(response.body()));
        } catch (JsonProcessingException e) {
            throw new UpstreamUnavailable("congestion-service sent a level this service could not read", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("congestion-service is unreachable at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted while calling congestion-service", e);
        }
    }

    /** A level that isn't a whole number from 0 to 8 is refused, never guessed at. */
    private static Reading read(JsonNode body) {
        JsonNode level = body.get("level");
        if (level == null || !level.isInt() || level.intValue() < 0 || level.intValue() > 8) {
            throw new UpstreamUnavailable("congestion-service sent a level this service could not read: " + body);
        }
        JsonNode updatedAt = body.path("updatedAt");
        return new Reading(level.intValue(), updatedAt.isTextual() ? updatedAt.asText() : null);
    }
}
