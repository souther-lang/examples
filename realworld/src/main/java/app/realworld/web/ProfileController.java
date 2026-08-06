// The HTTP boundary for profiles and the follow graph.
package app.realworld.web;

import blog.identity.CannotFollowSelf;
import blog.identity.FindUserByName;
import blog.identity.Follow;
import blog.identity.Followees;
import blog.identity.StoreUnfollow;
import blog.identity.User;
import blog.identity.UserNotFound;
import blog.identity.Username;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static app.realworld.souther.Decoding.decodeOrFail;

@RestController
public class ProfileController {

    private final FindUserByName findUserByName;
    private final Follow follow;
    private final StoreUnfollow storeUnfollow;
    private final Following following;

    public ProfileController(FindUserByName findUserByName,
                             Follow follow,
                             StoreUnfollow storeUnfollow,
                             Following following) {
        this.findUserByName = findUserByName;
        this.follow = follow;
        this.storeUnfollow = storeUnfollow;
        this.following = following;
    }

    /**
     * {@code GET /api/profiles/{username}}. Auth is optional: with no viewer the profile still reads,
     * and {@code following} is false because nobody is asking rather than because the answer is no.
     */
    @GetMapping("/api/profiles/{username}")
    public ResponseEntity<Object> profile(Viewer viewer, @PathVariable("username") String username) {
        User user = find(username);
        return ResponseEntity.ok(respond(user, following.of(viewer).contains(user.username())));
    }

    /**
     * {@code POST /api/profiles/{username}/follow}. Following yourself is the domain's refusal, not a
     * check written here — follow answers CannotFollowSelf and this only chooses the status for it.
     */
    @PostMapping("/api/profiles/{username}/follow")
    @Transactional
    public ResponseEntity<Object> followProfile(Viewer viewer, @PathVariable("username") String username) {
        User target = find(username);
        return switch (follow.apply(viewer.required(), target.username())) {
            case Followees followees ->
                    ResponseEntity.ok(respond(target, followees.usernames().contains(target.username())));
            case CannotFollowSelf _ ->
                    BoundaryErrors.unprocessable(List.of("you cannot follow yourself"));
        };
    }

    /**
     * {@code DELETE /api/profiles/{username}/follow}. Unfollowing states no rule — unfollowing
     * somebody you never followed is not an error — so there is no composed behavior to fold here and
     * the boundary calls the write directly.
     */
    @DeleteMapping("/api/profiles/{username}/follow")
    @Transactional
    public ResponseEntity<Object> unfollowProfile(Viewer viewer, @PathVariable("username") String username) {
        User target = find(username);
        Followees left = storeUnfollow.apply(viewer.required(), target.username());
        return ResponseEntity.ok(respond(target, left.usernames().contains(target.username())));
    }

    private Map<String, Object> respond(User user, boolean isFollowed) {
        return ConduitJson.envelope("profile",
                ConduitJson.profile(User.encoder().encode(user), isFollowed));
    }

    /** An unknown username is a 404 rather than a domain case: nothing was asked of the domain yet. */
    private User find(String username) {
        Username name = decodeOrFail(Username.decoder(), username);
        return switch (findUserByName.apply(name)) {
            case User user -> user;
            case UserNotFound _ -> throw new NotFound();
        };
    }
}
