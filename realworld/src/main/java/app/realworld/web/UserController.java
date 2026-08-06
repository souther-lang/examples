// The HTTP boundary for registration, login and the current user.
//
// Every route is the same three steps: unwrap the spec's envelope and decode the inside into domain
// values, call one behavior, fold its output union into a status and a body. The controller cannot
// construct data — constructors are not public — so the only way in is a decoder, which is also what
// checks the invariants. Each fold is a switch over a generated sealed interface, so a case added to
// a behavior's output in a .sou fails this file to compile rather than falling through at runtime.
package app.realworld.web;

import blog.identity.EmailTaken;
import blog.identity.Credentials;
import blog.identity.FindUserByName;
import blog.identity.FindUserByNameResult;
import blog.identity.HashPassword;
import blog.identity.InvalidCredentials;
import blog.identity.LoginUser;
import blog.identity.Password;
import blog.identity.Registration;
import blog.identity.RegisterUser;
import blog.identity.StorePassword;
import blog.identity.UpdateUser;
import blog.identity.User;
import blog.identity.UserNotFound;
import blog.identity.Username;
import blog.identity.UsernameTaken;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static app.realworld.souther.Decoding.decodeOrFail;

@RestController
public class UserController {

    private final RegisterUser registerUser;
    private final LoginUser loginUser;
    private final UpdateUser updateUser;
    private final FindUserByName findUserByName;
    private final HashPassword hashPassword;
    private final StorePassword storePassword;
    private final JwtTokens tokens;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public UserController(RegisterUser registerUser,
                          LoginUser loginUser,
                          UpdateUser updateUser,
                          FindUserByName findUserByName,
                          HashPassword hashPassword,
                          StorePassword storePassword,
                          JwtTokens tokens,
                          JsonMapper json,
                          TransactionTemplate tx) {
        this.registerUser = registerUser;
        this.loginUser = loginUser;
        this.updateUser = updateUser;
        this.findUserByName = findUserByName;
        this.hashPassword = hashPassword;
        this.storePassword = storePassword;
        this.tokens = tokens;
        this.json = json;
        this.tx = tx;
    }

    /**
     * {@code POST /api/users}. The two uniqueness rules are the domain's, and each answers its own
     * case, so the 422 says which name was taken rather than that registration failed.
     */
    @PostMapping("/api/users")
    public ResponseEntity<Object> register(@RequestBody JsonNode body) {
        Registration registration =
                decodeOrFail(Registration.jsonDecoder(), ConduitJson.inside(body, "user"));
        return tx.execute(_ -> switch (registerUser.apply(registration)) {
            case User user -> ResponseEntity.status(HttpStatus.CREATED).body(respond(user));
            case EmailTaken _ -> taken("email");
            case UsernameTaken _ -> taken("username");
        });
    }

    /**
     * {@code POST /api/users/login}. InvalidCredentials covers both an unknown address and a wrong
     * password, so the 401 is the same either way and the response says nothing about which accounts
     * exist.
     */
    @PostMapping("/api/users/login")
    public ResponseEntity<Object> login(@RequestBody JsonNode body) {
        Credentials credentials =
                decodeOrFail(Credentials.jsonDecoder(), ConduitJson.inside(body, "user"));
        return switch (loginUser.apply(credentials)) {
            case User user -> ResponseEntity.ok(respond(user));
            case InvalidCredentials _ -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        };
    }

    /** {@code GET /api/user}. The token is reissued so a client can keep using the response as-is. */
    @GetMapping("/api/user")
    public ResponseEntity<Object> current(Viewer viewer) {
        return ResponseEntity.ok(respond(currentUser(viewer.required())));
    }

    /**
     * {@code PUT /api/user}. The spec lets a client send any subset of the fields, so the boundary
     * overlays what arrived onto what is stored and hands the domain the user it wants written —
     * whether the two names it may have moved are free is the domain's decision, not this one.
     *
     * <p>The password is not part of a User and travels on its own: it is hashed here and written
     * beside the row, inside the same transaction as the change it arrived with.
     */
    @PutMapping("/api/user")
    public ResponseEntity<Object> update(Viewer viewer, @RequestBody JsonNode body) {
        return tx.execute(_ -> {
            User current = currentUser(viewer.required());
            JsonNode change = ConduitJson.inside(body, "user");

            // An encoder writes a Map and what is overlaid onto it arrived as JSON, so the stored user
            // is turned into a node first. That is the only reason valueToTree is here.
            ObjectNode merged = json.valueToTree(User.encoder().encode(current));
            overlay(merged, change, "username");
            overlay(merged, change, "email");
            overlay(merged, change, "bio");
            overlay(merged, change, "image");
            User wanted = decodeOrFail(User.jsonDecoder(), merged);

            return switch (updateUser.apply(current, wanted)) {
                case User stored -> {
                    changePassword(stored.username(), change);
                    yield ResponseEntity.ok(respond(stored));
                }
                case EmailTaken _ -> taken("email");
                case UsernameTaken _ -> taken("username");
            };
        });
    }

    // --- the pieces every route above shares ---

    /** The body a user response carries: the domain's fields, the absent optionals, and a token. */
    private Map<String, Object> respond(User user) {
        String token = tokens.issue(Username.encoder().encode(user.username()));
        return ConduitJson.envelope("user", ConduitJson.user(User.encoder().encode(user), token));
    }

    /**
     * The viewer's own row. A token this service signed names a user that existed when it was issued;
     * a row deleted since leaves a valid token with nobody behind it, which is not a domain outcome
     * of any behavior here — it is a request that can no longer be answered, so it is a 401.
     */
    private User currentUser(Username username) {
        return switch (findUserByName.apply(username)) {
            case User user -> user;
            case UserNotFound _ -> throw new Viewer.Unauthenticated();
        };
    }

    private void changePassword(Username username, JsonNode change) {
        JsonNode sent = change.get("password");
        if (sent != null && !sent.isNull()) {
            Password password = decodeOrFail(Password.jsonDecoder(), sent);
            storePassword.apply(username, hashPassword.apply(password));
        }
    }

    /**
     * A field the request actually sent. A key that is absent leaves what is stored alone; a key
     * explicitly null clears an optional, which is how the spec's clients empty a bio.
     */
    private static void overlay(ObjectNode merged, JsonNode change, String key) {
        JsonNode sent = change.get(key);
        if (sent == null) {
            return;
        }
        if (sent.isNull()) {
            merged.remove(key);
        } else {
            merged.set(key, sent);
        }
    }

    private static ResponseEntity<Object> taken(String field) {
        return BoundaryErrors.unprocessable(List.of(field + " has already been taken"));
    }
}
