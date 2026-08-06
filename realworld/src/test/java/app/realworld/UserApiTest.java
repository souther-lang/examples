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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registration and login endpoints over real HTTP, checked against what the RealWorld spec says
 * rather than against what this implementation happens to answer. Three of these are the mistakes
 * that make a conforming frontend fail against a backend that is otherwise correct: the envelope,
 * the {@code Token} scheme, and {@code image} arriving as a present null rather than an absent key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserApiTest {

    @Autowired DSLContext dsl;
    @Autowired Environment env;

    private ConduitClient api;

    @BeforeEach
    void reset() {
        dsl.deleteFrom(table(name("follows"))).execute();
        dsl.deleteFrom(table(name("users"))).execute();
        api = new ConduitClient(env);
    }

    @Test
    void registeringAnswers201WithATokenAndAnExplicitlyNullImage() {
        ConduitClient.Response created = register("jake", "jake@jake.jake", "jakejake");

        assertEquals(201, created.status());
        assertEquals("jake", created.text("user", "username"));
        assertNotNull(created.text("user", "token"));
        // The derived encoder drops an absent optional; the spec writes it as null, and a frontend
        // reading user.image was written against that.
        assertTrue(created.isExplicitNull("user", "image"), "image should be present and null");
        assertTrue(created.isExplicitNull("user", "bio"), "bio should be present and null");
    }

    @Test
    void aSecondRegistrationOnTheSameEmailIs422AndSaysWhichNameWasTaken() {
        register("jake", "jake@jake.jake", "jakejake");

        ConduitClient.Response again = register("newcomer", "jake@jake.jake", "jakejake");

        assertEquals(422, again.status());
        assertTrue(again.at("errors", "body", "0").asString().contains("email"),
                "the 422 should name the email, not just report a failure");
    }

    @Test
    void aSecondRegistrationOnTheSameUsernameIs422AndNamesTheUsername() {
        register("jake", "jake@jake.jake", "jakejake");

        ConduitClient.Response again = register("jake", "other@jake.jake", "jakejake");

        assertEquals(422, again.status());
        assertTrue(again.at("errors", "body", "0").asString().contains("username"));
    }

    @Test
    void aPasswordShorterThanTheInvariantIsRefusedByTheDecoder() {
        ConduitClient.Response created = register("jake", "jake@jake.jake", "short");

        assertEquals(422, created.status());
        assertTrue(created.has("errors", "body"));
    }

    @Test
    void theRightPasswordLogsInAndTheWrongOneIs401() {
        register("jake", "jake@jake.jake", "jakejake");

        ConduitClient.Response ok = login("jake@jake.jake", "jakejake");
        assertEquals(200, ok.status());
        assertNotNull(ok.text("user", "token"));

        assertEquals(401, login("jake@jake.jake", "wrongpass").status());
    }

    @Test
    void anUnknownEmailAnswersTheSame401AsAWrongPassword() {
        register("jake", "jake@jake.jake", "jakejake");

        // Same status and same (empty) body: nothing here tells a stranger which addresses exist.
        assertEquals(login("jake@jake.jake", "wrongpass").status(),
                login("nobody@example.com", "jakejake").status());
    }

    @Test
    void theCurrentUserIsReadWithTheTokenScheme() {
        String token = register("jake", "jake@jake.jake", "jakejake").text("user", "token");

        ConduitClient.Response me = api.get("/api/user", token);

        assertEquals(200, me.status());
        assertEquals("jake", me.text("user", "username"));
        assertEquals("jake@jake.jake", me.text("user", "email"));
    }

    @Test
    void theCurrentUserWithNoAuthorizationHeaderIs401() {
        assertEquals(401, api.get("/api/user", null).status());
    }

    @Test
    void bearerIsNotTheSpecsSchemeSoItIsNotAuthenticated() {
        String token = register("jake", "jake@jake.jake", "jakejake").text("user", "token");

        assertEquals(401, api.getWithRawAuthorization("/api/user", "Bearer " + token).status());
    }

    @Test
    void updatingSendsOnlyTheFieldsItChangesAndTheRestSurvive() {
        String token = register("jake", "jake@jake.jake", "jakejake").text("user", "token");

        ConduitClient.Response updated = api.put("/api/user", token,
                """
                {"user": {"bio": "I work at statefarm"}}
                """);

        assertEquals(200, updated.status());
        assertEquals("I work at statefarm", updated.text("user", "bio"));
        assertEquals("jake", updated.text("user", "username"));
        assertEquals("jake@jake.jake", updated.text("user", "email"));
    }

    @Test
    void movingToAnAddressSomebodyElseHoldsIs422() {
        register("gerome", "gerome@jake.jake", "jakejake");
        String token = register("jake", "jake@jake.jake", "jakejake").text("user", "token");

        ConduitClient.Response updated = api.put("/api/user", token,
                """
                {"user": {"email": "gerome@jake.jake"}}
                """);

        assertEquals(422, updated.status());
    }

    @Test
    void savingAProfileUnchangedIsNotReportedAsTakenAgainstItself() {
        String token = register("jake", "jake@jake.jake", "jakejake").text("user", "token");

        ConduitClient.Response updated = api.put("/api/user", token,
                """
                {"user": {"email": "jake@jake.jake", "username": "jake"}}
                """);

        assertEquals(200, updated.status());
    }

    @Test
    void aChangedPasswordIsTheOneThatLogsInAfterwards() {
        String token = register("jake", "jake@jake.jake", "jakejake").text("user", "token");

        api.put("/api/user", token, """
                {"user": {"password": "newpassword"}}
                """);

        assertEquals(401, login("jake@jake.jake", "jakejake").status());
        assertEquals(200, login("jake@jake.jake", "newpassword").status());
    }

    private ConduitClient.Response register(String username, String email, String password) {
        return api.post("/api/users", null, """
                {"user": {"username": "%s", "email": "%s", "password": "%s"}}
                """.formatted(username, email, password));
    }

    private ConduitClient.Response login(String email, String password) {
        return api.post("/api/users/login", null, """
                {"user": {"email": "%s", "password": "%s"}}
                """.formatted(email, password));
    }
}
