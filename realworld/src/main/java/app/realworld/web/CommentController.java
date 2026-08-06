// The HTTP boundary for comments.
//
// Writing one goes straight to the injected write, because commenting has no rule for the domain to
// decide. Deleting one goes through deleteComment, because it has exactly one — and the asymmetry
// between the two routes is the same asymmetry the .sou has.
package app.realworld.web;

import blog.articles.Article;
import blog.comments.Comment;
import blog.comments.CommentBody;
import blog.comments.CommentId;
import blog.comments.CommentNotFound;
import blog.comments.CommentThread;
import blog.comments.DeleteComment;
import blog.comments.FindComment;
import blog.comments.NotTheAuthor;
import blog.comments.ReadComments;
import blog.comments.Removed;
import blog.comments.StoreComment;
import blog.identity.FindUserByName;
import blog.identity.User;
import blog.identity.UserNotFound;
import blog.identity.Username;

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

import static app.realworld.souther.Decoding.decodeOrFail;

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
            Article article = articles.find(slug);
            User author = author(viewer.required());
            CommentBody text = decodeOrFail(CommentBody.jsonDecoder(),
                    ConduitJson.inside(body, "comment").path("body"));

            Comment stored = storeComment.apply(article.slug(), text, profileOf(author), now());
            return ResponseEntity.ok(one(stored, viewer));
        });
    }

    /** {@code GET /api/articles/{slug}/comments}. Optional auth; each author's flag is the viewer's. */
    @GetMapping("/api/articles/{slug}/comments")
    public ResponseEntity<Object> list(Viewer viewer, @PathVariable("slug") String slug) {
        Article article = articles.find(slug);
        CommentThread thread = readComments.apply(article.slug());
        Set<Username> followees = following.of(viewer);

        List<Map<String, Object>> body = thread.comments().stream()
                .map(comment -> ConduitJson.comment(Comment.encoder().encode(comment),
                        followees.contains(comment.author().username())))
                .toList();
        return ResponseEntity.ok(Map.of("comments", body));
    }

    /** {@code DELETE /api/articles/{slug}/comments/{id}}. A comment is its author's to delete. */
    @DeleteMapping("/api/articles/{slug}/comments/{id}")
    public ResponseEntity<Object> delete(Viewer viewer,
                                         @PathVariable("slug") String slug,
                                         @PathVariable("id") String id) {
        return tx.execute(_ -> {
            articles.find(slug);
            Comment comment = find(id);
            return switch (deleteComment.apply(comment, viewer.required())) {
                case Removed _ -> ResponseEntity.noContent().build();
                case NotTheAuthor _ -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            };
        });
    }

    // --- the pieces the routes share ---

    private Map<String, Object> one(Comment comment, Viewer viewer) {
        boolean followed = following.of(viewer).contains(comment.author().username());
        return ConduitJson.envelope("comment",
                ConduitJson.comment(Comment.encoder().encode(comment), followed));
    }

    private Comment find(String id) {
        CommentId commentId = decodeOrFail(CommentId.decoder(), Long.parseLong(id));
        return switch (findComment.apply(commentId)) {
            case Comment comment -> comment;
            case CommentNotFound _ -> throw new NotFound();
        };
    }

    private User author(Username username) {
        return switch (findUserByName.apply(username)) {
            case User user -> user;
            case UserNotFound _ -> throw new Viewer.Unauthenticated();
        };
    }

    private static blog.identity.Profile profileOf(User author) {
        Map<String, Object> raw = new LinkedHashMap<>(User.encoder().encode(author));
        raw.remove("email");
        return decodeOrFail(blog.identity.Profile.decoder(), raw);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
