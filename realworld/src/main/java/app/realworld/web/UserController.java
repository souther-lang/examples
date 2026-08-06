// The HTTP boundary for registration, login and the current user.
//
// Every route is the same three steps: unwrap the spec's envelope and decode the inside into domain
// values, call one behavior, fold its output union into a status and a body. The controller cannot
// construct data — constructors are not public — so the only way in is a decoder, which is also what
// checks the invariants. Each fold is a switch over a generated sealed interface, so a case added to
// a behavior's output in a .sou fails this file to compile rather than falling through at runtime.
package app.realworld.web;

import example.identity.EmailTaken;
import example.identity.Credentials;
import example.identity.FindUserByName;
import example.identity.FindUserByNameResult;
import example.identity.HashPassword;
import example.identity.InvalidCredentials;
import example.identity.LoginUser;
import example.identity.Password;
import example.identity.Registration;
import example.identity.RegisterUser;
import example.identity.StorePassword;
import example.identity.UpdateUser;
import example.identity.User;
import example.identity.UserNotFound;
import example.identity.Username;
import example.identity.UsernameTaken;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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

    public UserController(RegisterUser registerUser,
                          LoginUser loginUser,
                          UpdateUser updateUser,
                          FindUserByName findUserByName,
                          HashPassword hashPassword,
                          StorePassword storePassword,
                          JwtTokens tokens) {
        this.registerUser = registerUser;
        this.loginUser = loginUser;
        this.updateUser = updateUser;
        this.findUserByName = findUserByName;
        this.hashPassword = hashPassword;
        this.storePassword = storePassword;
        this.tokens = tokens;
    }

    /**
     * {@code POST /api/users}. The two uniqueness rules are the domain's, and each answers its own
     * case, so the 422 says which name was taken rather than that registration failed.
     */
    @PostMapping("/api/users")
    @Transactional
    public ResponseEntity<Object> register(@RequestBody Map<String, Object> body) {
        Registration registration = decodeOrFail(Registration.decoder(), inner(body, "user"));
        return switch (registerUser.apply(registration)) {
            case User user -> ResponseEntity.status(HttpStatus.CREATED).body(respond(user));
            case EmailTaken _ -> taken("email");
            case UsernameTaken _ -> taken("username");
        };
    }

    /**
     * {@code POST /api/users/login}. InvalidCredentials covers both an unknown address and a wrong
     * password, so the 401 is the same either way and the response says nothing about which accounts
     * exist.
     */
    @PostMapping("/api/users/login")
    public ResponseEntity<Object> login(@RequestBody Map<String, Object> body) {
        Credentials credentials = decodeOrFail(Credentials.decoder(), inner(body, "user"));
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
    @Transactional
    public ResponseEntity<Object> update(Viewer viewer, @RequestBody Map<String, Object> body) {
        User current = currentUser(viewer.required());
        Map<String, Object> change = inner(body, "user");

        Map<String, Object> merged = new LinkedHashMap<>(User.encoder().encode(current));
        overlay(merged, change, "username");
        overlay(merged, change, "email");
        overlay(merged, change, "bio");
        overlay(merged, change, "image");
        User wanted = decodeOrFail(User.decoder(), merged);

        return switch (updateUser.apply(current, wanted)) {
            case User stored -> {
                changePassword(stored.username(), change);
                yield ResponseEntity.ok(respond(stored));
            }
            case EmailTaken _ -> taken("email");
            case UsernameTaken _ -> taken("username");
        };
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

    private void changePassword(Username username, Map<String, Object> change) {
        Object raw = change.get("password");
        if (raw != null) {
            Password password = decodeOrFail(Password.decoder(), raw);
            storePassword.apply(username, hashPassword.apply(password));
        }
    }

    /**
     * A field the request actually sent. A key that is absent leaves what is stored alone; a key
     * explicitly null clears an optional, which is how the spec's clients empty a bio.
     */
    private static void overlay(Map<String, Object> merged, Map<String, Object> change, String key) {
        if (change.containsKey(key)) {
            if (change.get(key) == null) {
                merged.remove(key);
            } else {
                merged.put(key, change.get(key));
            }
        }
    }

    /** Every RealWorld request body is wrapped in one key naming what it holds. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> inner(Map<String, Object> body, String key) {
        Object nested = body == null ? null : body.get(key);
        return nested instanceof Map ? (Map<String, Object>) nested : Map.of();
    }

    private static ResponseEntity<Object> taken(String field) {
        return BoundaryErrors.unprocessable(List.of(field + " has already been taken"));
    }
}
