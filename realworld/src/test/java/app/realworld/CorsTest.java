package app.realworld;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A RealWorld frontend runs on its own origin, so every request it makes is cross-origin and the ones
 * carrying a token are preflighted first. If Authorization is not among the allowed headers the
 * browser never sends it and every authenticated request arrives anonymous — which reads as a broken
 * login rather than as a missing CORS header, so it is worth a test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsTest {

    @Autowired Environment env;

    @Test
    void aPreflightAllowsTheOriginTheMethodAndTheAuthorizationHeader() throws Exception {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        HttpRequest preflight = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/articles"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:4100")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .build();

        HttpResponse<String> response =
                HttpClient.newHttpClient().send(preflight, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:4100",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")
                .contains("POST"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .toLowerCase().contains("authorization"));
    }
}
