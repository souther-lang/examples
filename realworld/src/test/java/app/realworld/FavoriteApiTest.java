package app.realworld;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Favorites and tags. Favoriting twice is favoriting once — which the store's key enforces rather
 * than a counter anybody increments — and {@code favorited} is the viewer's answer while
 * {@code favoritesCount} is the article's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FavoriteApiTest {

    private static final String SLUG = "how-to-train-your-dragon";

    @Autowired DSLContext dsl;
    @Autowired Environment env;

    private ConduitClient api;
    private String jakesToken;
    private String geromesToken;

    @BeforeEach
    void reset() {
        dsl.deleteFrom(table(name("comments"))).execute();
        dsl.deleteFrom(table(name("favorites"))).execute();
        dsl.deleteFrom(table(name("article_tags"))).execute();
        dsl.deleteFrom(table(name("articles"))).execute();
        dsl.deleteFrom(table(name("follows"))).execute();
        dsl.deleteFrom(table(name("users"))).execute();
        api = new ConduitClient(env);
        jakesToken = register("jake", "jake@jake.jake");
        geromesToken = register("gerome", "gerome@jake.jake");
        api.post("/api/articles", jakesToken, """
                {"article": {"title": "How to train your dragon", "description": "d",
                             "body": "b", "tagList": ["dragons", "training"]}}
                """);
    }

    @Test
    void favoritingReportsItBackToTheViewerWhoDidIt() {
        ConduitClient.Response favorited = api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");

        assertEquals(200, favorited.status());
        assertTrue(favorited.bool("article", "favorited"));
        assertEquals(1, favorited.number("article", "favoritesCount"));
    }

    @Test
    void favoritingTwiceStillCountsOnce() {
        api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");
        ConduitClient.Response again = api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");

        assertEquals(1, again.number("article", "favoritesCount"),
                "the key makes the second one the same row, not a second");
    }

    @Test
    void aSecondPersonMakesItTwo() {
        api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");
        ConduitClient.Response byJake = api.post("/api/articles/" + SLUG + "/favorite", jakesToken, "");

        assertEquals(2, byJake.number("article", "favoritesCount"));
    }

    @Test
    void favoritedIsTheViewersAnswerAndTheCountIsTheArticles() {
        api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");

        ConduitClient.Response asJake = api.get("/api/articles/" + SLUG, jakesToken);

        assertFalse(asJake.bool("article", "favorited"), "jake did not favorite it");
        assertEquals(1, asJake.number("article", "favoritesCount"), "but somebody did");
    }

    @Test
    void unfavoritingPutsItBack() {
        api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");

        ConduitClient.Response removed = api.delete("/api/articles/" + SLUG + "/favorite", geromesToken);

        assertFalse(removed.bool("article", "favorited"));
        assertEquals(0, removed.number("article", "favoritesCount"));
    }

    @Test
    void unfavoritingWhatYouNeverFavoritedIsNotAnError() {
        assertEquals(200, api.delete("/api/articles/" + SLUG + "/favorite", geromesToken).status());
    }

    @Test
    void favoritingWithNoViewerIs401() {
        assertEquals(401, api.post("/api/articles/" + SLUG + "/favorite", null, "").status());
    }

    @Test
    void filteringByFavoritedFindsWhatThatPersonFavorited() {
        api.post("/api/articles/" + SLUG + "/favorite", geromesToken, "");

        assertEquals(1, api.get("/api/articles?favorited=gerome", null).number("articlesCount"));
        assertEquals(0, api.get("/api/articles?favorited=jake", null).number("articlesCount"));
    }

    @Test
    void theTagsInUseAreListedOnceEachWithNoAuth() {
        api.post("/api/articles", geromesToken, """
                {"article": {"title": "Dragons are real", "description": "d",
                             "body": "b", "tagList": ["dragons"]}}
                """);

        ConduitClient.Response tags = api.get("/api/tags", null);

        assertEquals(200, tags.status());
        List<String> listed = new ArrayList<>();
        tags.at("tags").forEach(node -> listed.add(node.asString()));
        assertEquals(List.of("dragons", "training"), listed);
    }

    private String register(String username, String email) {
        return api.post("/api/users", null, """
                {"user": {"username": "%s", "email": "%s", "password": "jakejake"}}
                """.formatted(username, email)).text("user", "token");
    }
}
