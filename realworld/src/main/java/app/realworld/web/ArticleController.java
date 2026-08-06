// The HTTP boundary for one article.
//
// The two authorization rules here are the domain's, not this file's: updateArticle and deleteArticle
// each answer NotTheAuthor, and all the controller does is choose 403 for it. A boundary that tested
// the author itself would be stating a rule the .sou already states, and the two would drift.
package app.realworld.web;

import example.articles.Article;
import example.articles.ArticleChange;
import example.articles.ArticleDraft;
import example.articles.ArticleNotFound;
import example.articles.ArticlePage;
import example.articles.ArticleQuery;
import example.articles.CreateArticle;
import example.articles.DeleteArticle;
import example.articles.NotTheAuthor;
import example.articles.ReadArticle;
import example.articles.ReadArticles;
import example.articles.Removed;
import example.articles.Slug;
import example.articles.SlugTaken;
import example.articles.TitleHasNoSlug;
import example.articles.UpdateArticle;
import example.identity.FindUserByName;
import example.identity.User;
import example.identity.UserNotFound;
import example.identity.Username;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.realworld.souther.Decoding.decodeOrFail;

@RestController
public class ArticleController {

    private final CreateArticle createArticle;
    private final UpdateArticle updateArticle;
    private final DeleteArticle deleteArticle;
    private final ReadArticle readArticle;
    private final ReadArticles readArticles;
    private final FindUserByName findUserByName;
    private final ArticleViews views;
    private final Following following;

    public ArticleController(CreateArticle createArticle,
                             UpdateArticle updateArticle,
                             DeleteArticle deleteArticle,
                             ReadArticle readArticle,
                             ReadArticles readArticles,
                             FindUserByName findUserByName,
                             ArticleViews views,
                             Following following) {
        this.createArticle = createArticle;
        this.updateArticle = updateArticle;
        this.deleteArticle = deleteArticle;
        this.readArticle = readArticle;
        this.readArticles = readArticles;
        this.findUserByName = findUserByName;
        this.views = views;
        this.following = following;
    }

    /**
     * {@code POST /api/articles}. The client sends what it wants written; who is writing and when are
     * the boundary's to supply, because a client claiming either would be a client the domain believed.
     * The slug is made from the title by the domain and never sent.
     */
    @PostMapping("/api/articles")
    @Transactional
    public ResponseEntity<Object> create(Viewer viewer, @RequestBody Map<String, Object> body) {
        User author = author(viewer.required());

        Map<String, Object> raw = new LinkedHashMap<>(inner(body, "article"));
        raw.put("author", authorProfile(author));
        raw.put("at", now().toString());
        ArticleDraft draft = decodeOrFail(ArticleDraft.decoder(), raw);

        return switch (createArticle.apply(draft)) {
            case Article article ->
                    ResponseEntity.status(HttpStatus.CREATED).body(views.one(article, viewer));
            case SlugTaken _ ->
                    BoundaryErrors.unprocessable(List.of("title has already been taken"));
            case TitleHasNoSlug _ ->
                    BoundaryErrors.unprocessable(List.of("title cannot be turned into a slug"));
        };
    }

    /**
     * {@code GET /api/articles}. The controller does not check the query parameters: it puts them in a
     * map and hands them to the decoder, and Limit's and Offset's invariants are what refuse a page of
     * a thousand. `favorited` is the spec's name for the parameter and `favoritedBy` is the domain's
     * name for what it means, which is the sort of renaming a boundary is for.
     */
    @GetMapping("/api/articles")
    public ResponseEntity<Object> list(Viewer viewer, @RequestParam Map<String, String> params) {
        Map<String, Object> raw = new LinkedHashMap<>(paging(params));
        raw.put("type", "GlobalQuery");
        putIfSent(raw, "tag", params.get("tag"));
        putIfSent(raw, "author", params.get("author"));
        putIfSent(raw, "favoritedBy", params.get("favorited"));
        return page(decodeOrFail(ArticleQuery.decoder(), raw), viewer);
    }

    /**
     * {@code GET /api/articles/feed}. A feed is the articles of the people you follow, so the followee
     * set is read once and travels inside the query — a FeedQuery has no tag or author to filter by,
     * because a feed is not asked those questions.
     *
     * <p>Declared before {@code /api/articles/{slug}} so the literal path is not read as a slug.
     */
    @GetMapping("/api/articles/feed")
    public ResponseEntity<Object> feed(Viewer viewer, @RequestParam Map<String, String> params) {
        Map<String, Object> raw = new LinkedHashMap<>(paging(params));
        raw.put("type", "FeedQuery");
        viewer.required();                       // a feed with nobody asking is a 401, not an empty page
        raw.put("followees", following.of(viewer).stream()
                .map(Username.encoder()::encode)
                .toList());
        return page(decodeOrFail(ArticleQuery.decoder(), raw), viewer);
    }

    /** {@code GET /api/articles/{slug}}. Auth is optional; the two flags follow whoever is asking. */
    @GetMapping("/api/articles/{slug}")
    public ResponseEntity<Object> read(Viewer viewer, @PathVariable("slug") String slug) {
        return ResponseEntity.ok(views.one(find(slug), viewer));
    }

    /** {@code PUT /api/articles/{slug}}. Any subset of the editable fields; the slug does not move. */
    @PutMapping("/api/articles/{slug}")
    @Transactional
    public ResponseEntity<Object> update(Viewer viewer,
                                         @PathVariable("slug") String slug,
                                         @RequestBody Map<String, Object> body) {
        Article article = find(slug);

        Map<String, Object> raw = new LinkedHashMap<>(inner(body, "article"));
        raw.put("at", now().toString());
        ArticleChange change = decodeOrFail(ArticleChange.decoder(), raw);

        return switch (updateArticle.apply(article, viewer.required(), change)) {
            case Article updated -> ResponseEntity.ok(views.one(updated, viewer));
            case NotTheAuthor _ -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        };
    }

    /** {@code DELETE /api/articles/{slug}}. The same rule as editing, answered the same way. */
    @DeleteMapping("/api/articles/{slug}")
    @Transactional
    public ResponseEntity<Object> delete(Viewer viewer, @PathVariable("slug") String slug) {
        return switch (deleteArticle.apply(find(slug), viewer.required())) {
            case Removed _ -> ResponseEntity.noContent().build();
            case NotTheAuthor _ -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        };
    }

    // --- the pieces the routes share ---

    Article find(String slug) {
        return switch (readArticle.apply(decodeOrFail(Slug.decoder(), slug))) {
            case Article article -> article;
            case ArticleNotFound _ -> throw new NotFound();
        };
    }

    private ResponseEntity<Object> page(ArticleQuery query, Viewer viewer) {
        ArticlePage found = readArticles.apply(query);
        return ResponseEntity.ok(views.page(found.articles(), (int) found.total(), viewer));
    }

    /** The spec's defaults: twenty articles from the start. */
    private static Map<String, Object> paging(Map<String, String> params) {
        return Map.of(
                "limit", Integer.parseInt(params.getOrDefault("limit", "20")),
                "offset", Integer.parseInt(params.getOrDefault("offset", "0")));
    }

    private static void putIfSent(Map<String, Object> raw, String key, String value) {
        if (value != null && !value.isBlank()) {
            raw.put(key, value);
        }
    }

    private User author(Username username) {
        return switch (findUserByName.apply(username)) {
            case User user -> user;
            case UserNotFound _ -> throw new Viewer.Unauthenticated();
        };
    }

    /** An article's author is a Profile, which is a User with the address nobody else sees removed. */
    private static Map<String, Object> authorProfile(User author) {
        Map<String, Object> profile = new LinkedHashMap<>(User.encoder().encode(author));
        profile.remove("email");
        return profile;
    }

    /**
     * The spec's timestamps carry milliseconds, so the value written is truncated to them: a stored
     * time with more precision than the response can state would answer differently on the way back.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inner(Map<String, Object> body, String key) {
        Object nested = body == null ? null : body.get(key);
        return nested instanceof Map ? (Map<String, Object>) nested : Map.of();
    }
}
