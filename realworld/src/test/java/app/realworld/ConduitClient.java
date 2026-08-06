// A small HTTP client for the API tests. Every test in this module drives real HTTP against a real
// Tomcat (as ordering's do) rather than MockMvc, because what these tests are for is that somebody
// else's frontend can talk to this service — and a frontend talks over HTTP.
package app.realworld;

import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** A request, and the status and parsed body it answered with. */
public final class ConduitClient {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final Environment env;

    public ConduitClient(Environment env) {
        this.env = env;
    }

    /** What an endpoint answered: the status, and the body parsed as JSON (missing when empty). */
    public record Response(int status, JsonNode body) {

        public String text(String... path) {
            JsonNode at = at(path);
            return at.isNull() ? null : at.asString();
        }

        public boolean bool(String... path) {
            return at(path).asBoolean();
        }

        public int number(String... path) {
            return at(path).asInt();
        }

        /** Present-and-null, which the spec writes for an absent bio or image. */
        public boolean isExplicitNull(String... path) {
            return at(path).isNull();
        }

        public boolean has(String... path) {
            return !at(path).isMissingNode();
        }

        /** A step that is all digits indexes an array; Jackson's path(String) would miss it. */
        public JsonNode at(String... path) {
            JsonNode node = body;
            for (String step : path) {
                node = node.isArray() && step.chars().allMatch(Character::isDigit)
                        ? node.path(Integer.parseInt(step))
                        : node.path(step);
            }
            return node;
        }
    }

    public Response get(String path, String token) {
        return send(request(path, token).GET());
    }

    public Response post(String path, String token, String json) {
        return send(request(path, token).POST(body(json)));
    }

    public Response put(String path, String token, String json) {
        return send(request(path, token).PUT(body(json)));
    }

    public Response delete(String path, String token) {
        return send(request(path, token).DELETE());
    }

    /** The spec's scheme is `Token`, not `Bearer`; a test that wants the wrong one passes it here. */
    public Response getWithRawAuthorization(String path, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri(path))
                .header("Content-Type", "application/json; charset=UTF-8");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return send(builder.GET());
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri(path))
                .header("Content-Type", "application/json; charset=UTF-8");
        if (token != null) {
            builder.header("Authorization", "Token " + token);
        }
        return builder;
    }

    private static HttpRequest.BodyPublisher body(String json) {
        return HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
    }

    private URI uri(String path) {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        return URI.create("http://localhost:" + port + path);
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = response.body();
            JsonNode parsed = text == null || text.isBlank()
                    ? JSON.missingNode()
                    : JSON.readTree(text);
            return new Response(response.statusCode(), parsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
