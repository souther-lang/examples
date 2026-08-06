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
 * Profiles and the follow graph. What is worth holding here is that {@code following} answers about
 * the viewer rather than about the profile: the same profile reports true to one caller and false to
 * another, and false again to a caller who did not say who they were.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileApiTest {

    @Autowired DSLContext dsl;
    @Autowired Environment env;

    private ConduitClient api;
    private String jakesToken;

    @BeforeEach
    void reset() {
        dsl.deleteFrom(table(name("follows"))).execute();
        dsl.deleteFrom(table(name("users"))).execute();
        api = new ConduitClient(env);
        jakesToken = register("jake", "jake@jake.jake");
        register("gerome", "gerome@jake.jake");
    }

    @Test
    void aProfileReadsWithNoViewerAndSaysFollowingIsFalse() {
        ConduitClient.Response profile = api.get("/api/profiles/gerome", null);

        assertEquals(200, profile.status());
        assertEquals("gerome", profile.text("profile", "username"));
        assertFalse(profile.bool("profile", "following"));
        assertTrue(profile.isExplicitNull("profile", "bio"));
    }

    @Test
    void aProfileNeverCarriesTheAddressOnlyItsOwnerSees() {
        ConduitClient.Response profile = api.get("/api/profiles/gerome", jakesToken);

        assertFalse(profile.has("profile", "email"), "a profile is public; an email is not");
    }

    @Test
    void anUnknownUsernameIs404() {
        assertEquals(404, api.get("/api/profiles/nobody", null).status());
    }

    @Test
    void followingIsReportedBackToTheViewerWhoDidIt() {
        ConduitClient.Response followed = api.post("/api/profiles/gerome/follow", jakesToken, "");

        assertEquals(200, followed.status());
        assertTrue(followed.bool("profile", "following"));
        assertTrue(api.get("/api/profiles/gerome", jakesToken).bool("profile", "following"));
    }

    @Test
    void thatSameProfileStillReportsFalseToEverybodyElse() {
        api.post("/api/profiles/gerome/follow", jakesToken, "");

        assertFalse(api.get("/api/profiles/gerome", null).bool("profile", "following"));
    }

    @Test
    void followingTwiceLeavesTheAnswerUnchanged() {
        api.post("/api/profiles/gerome/follow", jakesToken, "");
        ConduitClient.Response again = api.post("/api/profiles/gerome/follow", jakesToken, "");

        assertEquals(200, again.status());
        assertTrue(again.bool("profile", "following"));
    }

    @Test
    void unfollowingPutsItBack() {
        api.post("/api/profiles/gerome/follow", jakesToken, "");

        ConduitClient.Response unfollowed = api.delete("/api/profiles/gerome/follow", jakesToken);

        assertEquals(200, unfollowed.status());
        assertFalse(unfollowed.bool("profile", "following"));
    }

    @Test
    void unfollowingSomebodyYouNeverFollowedIsNotAnError() {
        assertEquals(200, api.delete("/api/profiles/gerome/follow", jakesToken).status());
    }

    @Test
    void followingYourselfIs422() {
        assertEquals(422, api.post("/api/profiles/jake/follow", jakesToken, "").status());
    }

    @Test
    void followingWithNoViewerIs401() {
        assertEquals(401, api.post("/api/profiles/gerome/follow", null, "").status());
    }

    private String register(String username, String email) {
        return api.post("/api/users", null, """
                {"user": {"username": "%s", "email": "%s", "password": "jakejake"}}
                """.formatted(username, email)).text("user", "token");
    }
}
