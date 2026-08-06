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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comments. The rule worth holding is the one comments.sou states — a comment is its author's to
 * delete — and the shape of the module says which of the two operations carries a decision: writing
 * one asks the domain nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommentApiTest {

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
                             "body": "b", "tagList": []}}
                """);
    }

    @Test
    void aCommentComesBackWithAnIdAndTheSpecsTimestamps() {
        ConduitClient.Response posted = comment(geromesToken, "His name was my name too.");

        assertEquals(200, posted.status());
        assertTrue(posted.number("comment", "id") >= 1);
        assertEquals("His name was my name too.", posted.text("comment", "body"));
        assertTrue(posted.text("comment", "createdAt")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        assertEquals("gerome", posted.text("comment", "author", "username"));
        assertTrue(posted.isExplicitNull("comment", "author", "bio"));
    }

    @Test
    void theThreadListsThemOldestFirstWithNoViewerAtAll() {
        comment(geromesToken, "first");
        comment(jakesToken, "second");

        ConduitClient.Response thread = api.get("/api/articles/" + SLUG + "/comments", null);

        assertEquals(200, thread.status());
        assertEquals(2, thread.at("comments").size());
        assertEquals("first", thread.text("comments", "0", "body"));
    }

    @Test
    void eachAuthorsFollowingFlagFollowsWhoeverIsReading() {
        comment(geromesToken, "His name was my name too.");
        api.post("/api/profiles/gerome/follow", jakesToken, "");

        assertTrue(api.get("/api/articles/" + SLUG + "/comments", jakesToken)
                .bool("comments", "0", "author", "following"));
    }

    @Test
    void somebodyWhoDidNotWriteItMayNotDeleteIt() {
        int id = comment(geromesToken, "His name was my name too.").number("comment", "id");

        assertEquals(403, api.delete("/api/articles/" + SLUG + "/comments/" + id, jakesToken).status());
    }

    @Test
    void theAuthorMayDeleteItAndItLeavesTheThread() {
        int id = comment(geromesToken, "His name was my name too.").number("comment", "id");

        assertEquals(204, api.delete("/api/articles/" + SLUG + "/comments/" + id, geromesToken).status());
        assertEquals(0, api.get("/api/articles/" + SLUG + "/comments", null).at("comments").size());
    }

    @Test
    void anEmptyCommentIsRefusedByTheDecoder() {
        assertEquals(422, comment(geromesToken, "").status());
    }

    @Test
    void commentingWithNoViewerIs401() {
        assertEquals(401, api.post("/api/articles/" + SLUG + "/comments", null,
                """
                {"comment": {"body": "anonymous"}}
                """).status());
    }

    @Test
    void commentingOnAnArticleThatIsNotThereIs404() {
        assertEquals(404, api.post("/api/articles/no-such-thing/comments", geromesToken,
                """
                {"comment": {"body": "hello"}}
                """).status());
    }

    private ConduitClient.Response comment(String token, String body) {
        return api.post("/api/articles/" + SLUG + "/comments", token,
                """
                {"comment": {"body": "%s"}}
                """.formatted(body));
    }

    private String register(String username, String email) {
        return api.post("/api/users", null, """
                {"user": {"username": "%s", "email": "%s", "password": "jakejake"}}
                """.formatted(username, email)).text("user", "token");
    }
}
