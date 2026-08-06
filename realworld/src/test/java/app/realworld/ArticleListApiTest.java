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
 * Listing and the feed. Two of these hold spec details a frontend depends on and an implementation
 * gets wrong quietly: {@code articlesCount} is how many the query matched rather than how many came
 * back on this page, and a list entry carries no {@code body} (the spec dropped it in 2024).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArticleListApiTest {

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

        write(jakesToken, "How to train your dragon", "dragons", "training");
        write(jakesToken, "Dragons are real", "dragons");
        write(geromesToken, "On functional programming", "fp");
    }

    @Test
    void everyArticleIsListedWithNoViewerAtAll() {
        ConduitClient.Response listed = api.get("/api/articles", null);

        assertEquals(200, listed.status());
        assertEquals(3, listed.at("articles").size());
        assertEquals(3, listed.number("articlesCount"));
    }

    @Test
    void articlesCountIsWhatTheQueryMatchedRatherThanWhatThisPageHolds() {
        ConduitClient.Response listed = api.get("/api/articles?limit=1", null);

        assertEquals(1, listed.at("articles").size());
        assertEquals(3, listed.number("articlesCount"), "the total, not the page size");
    }

    @Test
    void aListEntryCarriesNoBody() {
        ConduitClient.Response listed = api.get("/api/articles", null);

        assertFalse(listed.has("articles", "0", "body"),
                "the spec dropped body from list entries in 2024");
        assertTrue(listed.has("articles", "0", "description"));
    }

    @Test
    void aListEntryStillCarriesTheViewerShapedFlags() {
        ConduitClient.Response listed = api.get("/api/articles", jakesToken);

        assertTrue(listed.has("articles", "0", "favorited"));
        assertTrue(listed.has("articles", "0", "favoritesCount"));
        assertTrue(listed.has("articles", "0", "author", "following"));
    }

    @Test
    void filteringByTagKeepsOnlyTheArticlesCarryingIt() {
        ConduitClient.Response listed = api.get("/api/articles?tag=dragons", null);

        assertEquals(2, listed.at("articles").size());
        assertEquals(2, listed.number("articlesCount"));
    }

    @Test
    void filteringByAuthorKeepsOnlyTheirs() {
        ConduitClient.Response listed = api.get("/api/articles?author=gerome", null);

        assertEquals(1, listed.at("articles").size());
        assertEquals("on-functional-programming", listed.text("articles", "0", "slug"));
    }

    @Test
    void aPageOfNothingIsRefusedByTheDecoderRatherThanBySql() {
        assertEquals(422, api.get("/api/articles?limit=0", null).status());
        assertEquals(422, api.get("/api/articles?limit=1000", null).status());
    }

    /**
     * A limit that is not a number at all reaches the same decoder as a limit out of range, so it is
     * answered the same way. The controller does no parsing of its own: an {@code Integer.parseInt}
     * above the decoder would answer this by throwing, and what it threw carried no path and no code
     * and left as a 500.
     */
    @Test
    void aLimitThatIsNotANumberIsRefusedTheWayALimitOutOfRangeIs() {
        ConduitClient.Response refused = api.get("/api/articles?limit=abc", null);

        assertEquals(422, refused.status());
        assertTrue(refused.at("errors", "body", "0").asString().contains("limit"),
                "the 422 should name the parameter that broke");
        assertEquals(422, api.get("/api/articles?offset=nope", null).status());
    }

    @Test
    void aFeedIsTheArticlesOfThePeopleYouFollow() {
        api.post("/api/profiles/jake/follow", geromesToken, "");

        ConduitClient.Response feed = api.get("/api/articles/feed", geromesToken);

        assertEquals(200, feed.status());
        assertEquals(2, feed.number("articlesCount"));
        assertEquals("jake", feed.text("articles", "0", "author", "username"));
    }

    @Test
    void aFeedOfNobodyIsEmptyRatherThanEverything() {
        ConduitClient.Response feed = api.get("/api/articles/feed", jakesToken);

        assertEquals(200, feed.status());
        assertEquals(0, feed.number("articlesCount"));
    }

    @Test
    void aFeedWithNoViewerIs401() {
        assertEquals(401, api.get("/api/articles/feed", null).status());
    }

    private void write(String token, String title, String... tags) {
        String tagList = String.join(", ", java.util.Arrays.stream(tags).map(t -> "\"" + t + "\"").toList());
        api.post("/api/articles", token, """
                {"article": {"title": "%s", "description": "d", "body": "b", "tagList": [%s]}}
                """.formatted(title, tagList));
    }

    private String register(String username, String email) {
        return api.post("/api/users", null, """
                {"user": {"username": "%s", "email": "%s", "password": "jakejake"}}
                """.formatted(username, email)).text("user", "token");
    }
}
