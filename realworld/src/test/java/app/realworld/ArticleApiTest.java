package app.realworld;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One article over HTTP. Two of these are about the difference between the domain's JSON and the
 * spec's: the timestamp format, which Souther's {@code DateTime} does not produce on its own, and the
 * nested author's {@code following}, which is about whoever is reading rather than about the author.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArticleApiTest {

    private static final String DRAGONS = """
            {"article": {"title": "How to train your dragon",
                         "description": "Ever wonder how?",
                         "body": "It takes a Jacobian",
                         "tagList": ["dragons", "training"]}}
            """;

    @Autowired DSLContext dsl;
    @Autowired Environment env;

    private ConduitClient api;
    private String jakesToken;
    private String geromesToken;

    @BeforeEach
    void reset() {
        dsl.deleteFrom(table(name("favorites"))).execute();
        dsl.deleteFrom(table(name("article_tags"))).execute();
        dsl.deleteFrom(table(name("articles"))).execute();
        dsl.deleteFrom(table(name("follows"))).execute();
        dsl.deleteFrom(table(name("users"))).execute();
        api = new ConduitClient(env);
        jakesToken = register("jake", "jake@jake.jake");
        geromesToken = register("gerome", "gerome@jake.jake");
    }

    @Test
    void writingAnArticleMakesItsSlugFromItsTitle() {
        ConduitClient.Response created = api.post("/api/articles", jakesToken, DRAGONS);

        assertEquals(201, created.status());
        assertEquals("how-to-train-your-dragon", created.text("article", "slug"));
        assertEquals("How to train your dragon", created.text("article", "title"));
        assertEquals(2, created.at("article", "tagList").size());
    }

    @Test
    void theTimestampsAreUtcWithThreeFractionalDigits() {
        ConduitClient.Response created = api.post("/api/articles", jakesToken, DRAGONS);

        // Souther's DateTime is a LocalDateTime and encodes as its toString: no Z, and no fractional
        // part at all when the nanoseconds are zero. This is what ConduitJson.timestamp is for.
        String createdAt = created.text("article", "createdAt");
        assertTrue(createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "createdAt was " + createdAt);
        assertTrue(created.text("article", "updatedAt")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void aFreshArticleIsFavoritedByNobodyAndItsAuthorFollowedByNobody() {
        ConduitClient.Response created = api.post("/api/articles", jakesToken, DRAGONS);

        assertFalse(created.bool("article", "favorited"));
        assertEquals(0, created.number("article", "favoritesCount"));
        assertFalse(created.bool("article", "author", "following"));
        assertTrue(created.isExplicitNull("article", "author", "bio"));
    }

    @Test
    void theAuthorsFollowingFlagFollowsWhoeverIsReading() {
        api.post("/api/articles", jakesToken, DRAGONS);
        api.post("/api/profiles/jake/follow", geromesToken, "");

        assertTrue(api.get("/api/articles/how-to-train-your-dragon", geromesToken)
                .bool("article", "author", "following"));
        assertFalse(api.get("/api/articles/how-to-train-your-dragon", jakesToken)
                .bool("article", "author", "following"));
    }

    @Test
    void anArticleReadsWithNoViewerAtAll() {
        api.post("/api/articles", jakesToken, DRAGONS);

        ConduitClient.Response read = api.get("/api/articles/how-to-train-your-dragon", null);

        assertEquals(200, read.status());
        assertEquals("It takes a Jacobian", read.text("article", "body"));
        assertFalse(read.bool("article", "favorited"));
    }

    @Test
    void aSecondArticleWithTheSameTitleIs422() {
        api.post("/api/articles", jakesToken, DRAGONS);

        assertEquals(422, api.post("/api/articles", geromesToken, DRAGONS).status());
    }

    @Test
    void aTitleOfNothingButPunctuationNamesNoArticle() {
        ConduitClient.Response created = api.post("/api/articles", jakesToken, """
                {"article": {"title": "???", "description": "d", "body": "b", "tagList": []}}
                """);

        assertEquals(422, created.status());
    }

    @Test
    void anUnknownSlugIs404() {
        assertEquals(404, api.get("/api/articles/no-such-thing", null).status());
    }

    @Test
    void theAuthorMayChangeTheTitleAndTheSlugDoesNotMove() {
        api.post("/api/articles", jakesToken, DRAGONS);

        ConduitClient.Response updated = api.put("/api/articles/how-to-train-your-dragon", jakesToken,
                """
                {"article": {"title": "How to train your dragon, again"}}
                """);

        assertEquals(200, updated.status());
        assertEquals("How to train your dragon, again", updated.text("article", "title"));
        assertEquals("how-to-train-your-dragon", updated.text("article", "slug"));
        assertEquals("It takes a Jacobian", updated.text("article", "body"));
    }

    @Test
    void somebodyElseMayNotChangeIt() {
        api.post("/api/articles", jakesToken, DRAGONS);

        assertEquals(403, api.put("/api/articles/how-to-train-your-dragon", geromesToken, """
                {"article": {"title": "Mine now"}}
                """).status());
    }

    @Test
    void somebodyElseMayNotDeleteItAndTheAuthorMay() {
        api.post("/api/articles", jakesToken, DRAGONS);

        assertEquals(403, api.delete("/api/articles/how-to-train-your-dragon", geromesToken).status());
        assertEquals(204, api.delete("/api/articles/how-to-train-your-dragon", jakesToken).status());
        assertEquals(404, api.get("/api/articles/how-to-train-your-dragon", null).status());
    }

    @Test
    void writingWithNoViewerIs401() {
        assertEquals(401, api.post("/api/articles", null, DRAGONS).status());
    }

    private String register(String username, String email) {
        return api.post("/api/users", null, """
                {"user": {"username": "%s", "email": "%s", "password": "jakejake"}}
                """.formatted(username, email)).text("user", "token");
    }
}
