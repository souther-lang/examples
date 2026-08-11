// The HTTP boundary for one article.
//
// The two authorization rules here are the domain's, not this file's: updateArticle and deleteArticle
// each answer NotTheAuthor, and all the controller does is choose 403 for it. A boundary that tested
// the author itself would be stating a rule the .sou already states, and the two would drift.
//
// A request body is a JsonNode read by jsonDecoder(); query parameters are a Map read by decoder().
// Souther derives one decoder per input source, so each source is read by the one made for it.
//
// Both of the things a route can be handed — a slug in the path and a body or a query string — are
// decoded into Results and folded here. Where a route reads two of them, they are combined with
// Result.map2 first, so a request that broke both is told about both rather than about whichever the
// controller happened to read first.
package app.realworld.web;

import blog.articles.Article;
import blog.articles.ArticleChange;
import blog.articles.ArticleDraft;
import blog.articles.ArticleNotFound;
import blog.articles.ArticlePage;
import blog.articles.ArticleQuery;
import blog.articles.CreateArticle;
import blog.articles.DeleteArticle;
import blog.articles.NotTheAuthor;
import blog.articles.ReadArticle;
import blog.articles.ReadArticleResult;
import blog.articles.ReadArticles;
import blog.articles.Removed;
import blog.articles.Slug;
import blog.articles.SlugTaken;
import blog.articles.StoreFavorite;
import blog.articles.StoreUnfavorite;
import blog.articles.TitleHasNoSlug;
import blog.articles.UpdateArticle;
import blog.identity.FindUserByName;
import blog.identity.User;
import blog.identity.UserNotFound;
import blog.identity.Username;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;
import net.unit8.raoh.decode.combinator.Tuple2;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final StoreFavorite storeFavorite;
    private final StoreUnfavorite storeUnfavorite;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public ArticleController(CreateArticle createArticle,
                             UpdateArticle updateArticle,
                             DeleteArticle deleteArticle,
                             ReadArticle readArticle,
                             ReadArticles readArticles,
                             FindUserByName findUserByName,
                             ArticleViews views,
                             Following following,
                             StoreFavorite storeFavorite,
                             StoreUnfavorite storeUnfavorite,
                             JsonMapper json,
                             TransactionTemplate tx) {
        this.createArticle = createArticle;
        this.updateArticle = updateArticle;
        this.deleteArticle = deleteArticle;
        this.readArticle = readArticle;
        this.readArticles = readArticles;
        this.findUserByName = findUserByName;
        this.views = views;
        this.following = following;
        this.storeFavorite = storeFavorite;
        this.storeUnfavorite = storeUnfavorite;
        this.json = json;
        this.tx = tx;
    }

    /**
     * {@code POST /api/articles}. The client sends what it wants written; who is writing and when are
     * the boundary's to supply, because a client claiming either would be a client the domain believed.
     * The slug is made from the title by the domain and never sent.
     */
    @PostMapping("/api/articles")
    public ResponseEntity<Object> create(Viewer viewer, @RequestBody JsonNode body) {
        return tx.execute(_ -> {
            User author = author(viewer.required());

            ObjectNode raw = ConduitJson.inside(body, "article").deepCopy();
            raw.set("author", json.valueToTree(authorProfile(author)));
            raw.put("at", now().toString());

            return switch (ArticleDraft.jsonDecoder().decode(raw)) {
                case Ok(ArticleDraft draft) -> switch (createArticle.apply(draft)) {
                    case Article article ->
                            ResponseEntity.status(HttpStatus.CREATED).body(views.one(article, viewer));
                    case SlugTaken _ ->
                            BoundaryErrors.unprocessable(List.of("title has already been taken"));
                    case TitleHasNoSlug _ ->
                            BoundaryErrors.unprocessable(List.of("title cannot be turned into a slug"));
                };
                case Err(var issues) -> BoundaryErrors.unprocessable(issues);
            };
        });
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
        return page(raw, viewer);
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
        return page(raw, viewer);
    }

    /** {@code GET /api/articles/{slug}}. Auth is optional; the two flags follow whoever is asking. */
    @GetMapping("/api/articles/{slug}")
    public ResponseEntity<Object> read(Viewer viewer, @PathVariable("slug") String slug) {
        return switch (find(slug)) {
            case Ok(Article article) -> ResponseEntity.ok(views.one(article, viewer));
            case Ok(ArticleNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        };
    }

    /** {@code PUT /api/articles/{slug}}. Any subset of the editable fields; the slug does not move. */
    @PutMapping("/api/articles/{slug}")
    public ResponseEntity<Object> update(Viewer viewer,
                                         @PathVariable("slug") String slug,
                                         @RequestBody JsonNode body) {
        return tx.execute(_ -> {
            ObjectNode raw = ConduitJson.inside(body, "article").deepCopy();
            raw.put("at", now().toString());

            // The slug and the body are both this request's, so both are read before either is
            // answered: a request that named no article and sent no valid change says so once.
            return switch (Result.map2(find(slug), ArticleChange.jsonDecoder().decode(raw), Tuple2::new)) {
                case Ok(Tuple2(Article article, ArticleChange change)) ->
                        switch (updateArticle.apply(article, viewer.required(), change)) {
                            case Article updated -> ResponseEntity.ok(views.one(updated, viewer));
                            case NotTheAuthor _ -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                        };
                case Ok(Tuple2(ArticleNotFound _, ArticleChange _)) -> ResponseEntity.notFound().build();
                case Err(var issues) -> BoundaryErrors.unprocessable(issues);
            };
        });
    }

    /**
     * {@code POST /api/articles/{slug}/favorite} and its DELETE. Favoriting states no rule — anybody
     * logged in may, and favoriting twice is favoriting once — so there is no composed behavior to
     * fold and the boundary calls the write directly, as it does for unfollowing.
     */
    @PostMapping("/api/articles/{slug}/favorite")
    public ResponseEntity<Object> favorite(Viewer viewer, @PathVariable("slug") String slug) {
        return tx.execute(_ -> switch (find(slug)) {
            case Ok(Article article) -> {
                storeFavorite.apply(viewer.required(), article.slug());
                yield ResponseEntity.ok(views.one(article, viewer));
            }
            case Ok(ArticleNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        });
    }

    @DeleteMapping("/api/articles/{slug}/favorite")
    public ResponseEntity<Object> unfavorite(Viewer viewer, @PathVariable("slug") String slug) {
        return tx.execute(_ -> switch (find(slug)) {
            case Ok(Article article) -> {
                storeUnfavorite.apply(viewer.required(), article.slug());
                yield ResponseEntity.ok(views.one(article, viewer));
            }
            case Ok(ArticleNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        });
    }

    /** {@code DELETE /api/articles/{slug}}. The same rule as editing, answered the same way. */
    @DeleteMapping("/api/articles/{slug}")
    public ResponseEntity<Object> delete(Viewer viewer, @PathVariable("slug") String slug) {
        return tx.execute(_ -> switch (find(slug)) {
            case Ok(Article article) -> switch (deleteArticle.apply(article, viewer.required())) {
                case Removed _ -> ResponseEntity.noContent().build();
                case NotTheAuthor _ -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            };
            case Ok(ArticleNotFound _) -> ResponseEntity.notFound().build();
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        });
    }

    // --- the pieces the routes share ---

    /**
     * What the path names: the decoded slug handed to the behavior that reads it. The two answers
     * stay apart — an Err is text that is not a slug, and an ArticleNotFound is a slug with no
     * article behind it — because the domain declared the second one and nobody declared the first.
     */
    Result<ReadArticleResult> find(String slug) {
        return Slug.decoder().decode(slug).map(readArticle::apply);
    }

    private ResponseEntity<Object> page(Map<String, Object> raw, Viewer viewer) {
        return switch (ArticleQuery.decoder().decode(raw)) {
            case Ok(ArticleQuery query) -> {
                ArticlePage found = readArticles.apply(query);
                yield ResponseEntity.ok(views.page(found.articles(), (int) found.total(), viewer));
            }
            case Err(var issues) -> BoundaryErrors.unprocessable(issues);
        };
    }

    /**
     * The spec's defaults: twenty articles from the start. Nothing is validated here — Limit's and
     * Offset's invariants are the domain's, and the decoder is what checks them.
     */
    private static Map<String, Object> paging(Map<String, String> params) {
        return Map.of(
                "limit", number(params.getOrDefault("limit", "20")),
                "offset", number(params.getOrDefault("offset", "0")));
    }

    /**
     * A query parameter arrives as text whatever it means. One that reads as a number is handed to
     * the decoder as one, and anything else is handed over as it came so the decoder is what refuses
     * it. {@code Integer.parseInt} here would answer by throwing, and what it threw carried no path
     * and no code, so {@code ?limit=abc} left as a 500 while {@code ?limit=0} was a 422.
     */
    private static Object number(String raw) {
        return raw.matches("-?[0-9]{1,18}") ? Long.valueOf(raw) : raw;
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
     * A DateTime holds no fraction of a second, so the clock reading is truncated to one before the
     * domain is handed it. The milliseconds the spec's timestamps carry are written back as zeros by
     * {@link ConduitJson#timestamp}; a value with more precision than the type holds is refused at
     * the decoder rather than stored.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
