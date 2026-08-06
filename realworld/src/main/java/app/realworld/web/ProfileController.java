// The HTTP boundary for profiles and the follow graph.
//
// Every route reads a username out of the path, and the two things that can go wrong with one are
// answered separately: text that is not a username at all is a decoder's refusal, and a username
// nobody holds is what findUserByName answers. Both arrive as values, so both are folded here.
package app.realworld.web;

import blog.identity.CannotFollowSelf;
import blog.identity.FindUserByName;
import blog.identity.FindUserByNameResult;
import blog.identity.Follow;
import blog.identity.Followees;
import blog.identity.StoreUnfollow;
import blog.identity.User;
import blog.identity.UserNotFound;
import blog.identity.Username;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ProfileController {

    private final FindUserByName findUserByName;
    private final Follow follow;
    private final StoreUnfollow storeUnfollow;
    private final Following following;
    private final TransactionTemplate tx;

    public ProfileController(FindUserByName findUserByName,
                             Follow follow,
                             StoreUnfollow storeUnfollow,
                             Following following,
                             TransactionTemplate tx) {
        this.findUserByName = findUserByName;
        this.follow = follow;
        this.storeUnfollow = storeUnfollow;
        this.following = following;
        this.tx = tx;
    }

    /**
     * {@code GET /api/profiles/{username}}. Auth is optional: with no viewer the profile still reads,
     * and {@code following} is false because nobody is asking rather than because the answer is no.
     */
    @GetMapping("/api/profiles/{username}")
    public ResponseEntity<Object> profile(Viewer viewer, @PathVariable("username") String username) {
        return switch (find(username)) {
            case Ok(User user) ->
                    ResponseEntity.ok(respond(user, following.of(viewer).contains(user.username())));
            case Ok(UserNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        };
    }

    /**
     * {@code POST /api/profiles/{username}/follow}. Following yourself is the domain's refusal, not a
     * check written here — follow answers CannotFollowSelf and this only chooses the status for it.
     */
    @PostMapping("/api/profiles/{username}/follow")
    public ResponseEntity<Object> followProfile(Viewer viewer, @PathVariable("username") String username) {
        return tx.execute(_ -> switch (find(username)) {
            case Ok(User target) -> switch (follow.apply(viewer.required(), target.username())) {
                case Followees followees ->
                        ResponseEntity.ok(respond(target, followees.usernames().contains(target.username())));
                case CannotFollowSelf _ ->
                        BoundaryErrors.unprocessable(List.of("you cannot follow yourself"));
            };
            case Ok(UserNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        });
    }

    /**
     * {@code DELETE /api/profiles/{username}/follow}. Unfollowing states no rule — unfollowing
     * somebody you never followed is not an error — so there is no composed behavior to fold here and
     * the boundary calls the write directly.
     */
    @DeleteMapping("/api/profiles/{username}/follow")
    public ResponseEntity<Object> unfollowProfile(Viewer viewer, @PathVariable("username") String username) {
        return tx.execute(_ -> switch (find(username)) {
            case Ok(User target) -> {
                Followees left = storeUnfollow.apply(viewer.required(), target.username());
                yield ResponseEntity.ok(respond(target, left.usernames().contains(target.username())));
            }
            case Ok(UserNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        });
    }

    private Map<String, Object> respond(User user, boolean isFollowed) {
        return ConduitJson.envelope("profile",
                ConduitJson.profile(User.encoder().encode(user), isFollowed));
    }

    /**
     * What the path names: the decoded username handed to the behavior that looks it up. The two
     * answers stay apart — an Err is text that is not a username, and a UserNotFound is a username
     * with nobody behind it — because they are not the same reply.
     */
    private Result<FindUserByNameResult> find(String username) {
        return Username.decoder().decode(username).map(findUserByName::apply);
    }
}
