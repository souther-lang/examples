// The HTTP boundary for comments.
//
// Writing one goes straight to the injected write, because commenting has no rule for the domain to
// decide. Deleting one goes through deleteComment, because it has exactly one — and the asymmetry
// between the two routes is the same asymmetry the .sou has.
package app.realworld.web;

import blog.articles.Article;
import blog.articles.ArticleNotFound;
import blog.comments.Comment;
import blog.comments.CommentBody;
import blog.comments.CommentId;
import blog.comments.CommentNotFound;
import blog.comments.CommentThread;
import blog.comments.DeleteComment;
import blog.comments.FindComment;
import blog.comments.FindCommentResult;
import blog.comments.NotTheAuthor;
import blog.comments.ReadComments;
import blog.comments.Removed;
import blog.comments.StoreComment;
import blog.identity.FindUserByName;
import blog.identity.User;
import blog.identity.UserNotFound;
import blog.identity.Username;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;
import net.unit8.raoh.decode.combinator.Tuple2;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class CommentController {

    private final StoreComment storeComment;
    private final ReadComments readComments;
    private final FindComment findComment;
    private final DeleteComment deleteComment;
    private final FindUserByName findUserByName;
    private final ArticleController articles;
    private final Following following;
    private final TransactionTemplate tx;

    public CommentController(StoreComment storeComment,
                             ReadComments readComments,
                             FindComment findComment,
                             DeleteComment deleteComment,
                             FindUserByName findUserByName,
                             ArticleController articles,
                             Following following,
                             TransactionTemplate tx) {
        this.storeComment = storeComment;
        this.readComments = readComments;
        this.findComment = findComment;
        this.deleteComment = deleteComment;
        this.findUserByName = findUserByName;
        this.articles = articles;
        this.following = following;
        this.tx = tx;
    }

    /** {@code POST /api/articles/{slug}/comments}. Anybody logged in may comment, so no rule is asked. */
    @PostMapping("/api/articles/{slug}/comments")
    public ResponseEntity<Object> add(Viewer viewer,
                                      @PathVariable("slug") String slug,
                                      @RequestBody JsonNode body) {
        return tx.execute(_ -> {
            User author = author(viewer.required());
            JsonNode text = ConduitJson.inside(body, "comment").path("body");

            // The path the decoder is given is `/body`, because what it is handed is the field's own
            // value: it cannot know where that came from, so the 422 only says so if this line does.
            return switch (Result.map2(articles.find(slug),
                    CommentBody.jsonDecoder().decode(text, Path.of("body")), Tuple2::new)) {
                case Ok(Tuple2(Article article, CommentBody written)) -> {
                    Comment stored = storeComment.apply(article.slug(), written, profileOf(author), now());
                    yield ResponseEntity.ok(one(stored, viewer));
                }
                case Ok(Tuple2(ArticleNotFound _, CommentBody _)) -> ResponseEntity.notFound().build();
                case Err(var issues) -> BoundaryErrors.unprocessable(issues);
            };
        });
    }

    /** {@code GET /api/articles/{slug}/comments}. Optional auth; each author's flag is the viewer's. */
    @GetMapping("/api/articles/{slug}/comments")
    public ResponseEntity<Object> list(Viewer viewer, @PathVariable("slug") String slug) {
        return switch (articles.find(slug)) {
            case Ok(Article article) -> {
                CommentThread thread = readComments.apply(article.slug());
                Set<Username> followees = following.of(viewer);

                List<Map<String, Object>> body = thread.comments().stream()
                        .map(comment -> ConduitJson.comment(Comment.encoder().encode(comment),
                                followees.contains(comment.author().username())))
                        .toList();
                yield ResponseEntity.ok(Map.of("comments", body));
            }
            case Ok(ArticleNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        };
    }

    /** {@code DELETE /api/articles/{slug}/comments/{id}}. A comment is its author's to delete. */
    @DeleteMapping("/api/articles/{slug}/comments/{id}")
    public ResponseEntity<Object> delete(Viewer viewer,
                                         @PathVariable("slug") String slug,
                                         @PathVariable("id") String id) {
        // Two names in the path, so both are read before either is answered.
        return tx.execute(_ -> switch (Result.map2(articles.find(slug), find(id), Tuple2::new)) {
            case Ok(Tuple2(Article _, Comment comment)) ->
                    switch (deleteComment.apply(comment, viewer.required())) {
                        case Removed _ -> ResponseEntity.noContent().build();
                        case NotTheAuthor _ -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                    };
            case Ok(Tuple2(ArticleNotFound _, _)) -> ResponseEntity.notFound().build();
            case Ok(Tuple2(_, CommentNotFound _)) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        });
    }

    // --- the pieces the routes share ---

    private Map<String, Object> one(Comment comment, Viewer viewer) {
        boolean followed = following.of(viewer).contains(comment.author().username());
        return ConduitJson.envelope("comment",
                ConduitJson.comment(Comment.encoder().encode(comment), followed));
    }

    /**
     * What the path names. An id arrives as text, so one that reads as a number is handed over as one
     * and anything else is handed over as it came — the decoder is then what refuses it, and the
     * answer is a 422 with a path like every other refusal. {@code Long.parseLong} here would answer
     * by throwing, and what it threw carried neither.
     */
    private Result<FindCommentResult> find(String id) {
        Object sent = id.matches("[0-9]{1,18}") ? Long.valueOf(id) : id;
        return CommentId.decoder().decode(sent).map(findComment::apply);
    }

    private User author(Username username) {
        return switch (findUserByName.apply(username)) {
            case User user -> user;
            case UserNotFound _ -> throw new Viewer.Unauthenticated();
        };
    }

    /**
     * A stored user read back as the profile that wrote a comment. This decode is not reading outside
     * input — what it is handed was encoded from a User two lines above — so a refusal here is this
     * process failing to read its own writing, which is a fault rather than an answer to the caller.
     */
    private static blog.identity.Profile profileOf(User author) {
        Map<String, Object> raw = new LinkedHashMap<>(User.encoder().encode(author));
        raw.remove("email");
        return blog.identity.Profile.decoder().decode(raw).orElseThrow(issues ->
                new IllegalStateException("a stored user did not read back as a profile: " + issues));
    }

    /** A DateTime holds no fraction of a second, so the clock reading is truncated to one. */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
