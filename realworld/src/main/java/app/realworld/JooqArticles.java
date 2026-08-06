// The jOOQ implementations of articles.sou's injected behaviors.
//
// Reading an article joins the author's row, because an Article carries a Profile rather than a name.
// That is one join here instead of a lookup per row at the boundary, which is what the domain holding
// the person rather than the id buys.
package app.realworld;

import blog.articles.Article;
import blog.articles.ArticlePage;
import blog.articles.ArticleQuery;
import blog.articles.FavoriteCounts;
import blog.articles.FavoritedSlugs;
import blog.articles.FeedQuery;
import blog.articles.GlobalQuery;
import blog.articles.ReadArticle;
import blog.articles.ReadArticleResult;
import blog.articles.ReadArticles;
import blog.articles.ReadFavoriteCounts;
import blog.articles.ReadFavorited;
import blog.articles.Removed;
import blog.articles.RemoveArticle;
import blog.articles.Slug;
import blog.articles.SlugExists;
import blog.articles.ReadTags;
import blog.articles.StoreArticle;
import blog.articles.StoreFavorite;
import blog.articles.StoreUnfavorite;
import blog.articles.TagList;
import blog.articles.Tag;
import blog.identity.Username;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;

import souther.runtime.Option;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

public final class JooqArticles {

    private JooqArticles() {
    }

    /** slugExists: whether the slug an article's title produced already names one. */
    public static final class SlugIsTaken extends SlugExists {

        private final DSLContext dsl;

        public SlugIsTaken(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Boolean apply(Slug slug) {
            return dsl.fetchExists(table(name("articles")),
                    field(name("slug"), String.class).eq(Slug.encoder().encode(slug)));
        }
    }

    /**
     * storeArticle: the article row and its tags. It serves both createArticle and updateArticle, so
     * it writes whichever of the two is needed — an update that matched no row is an insert.
     */
    public static final class Store extends StoreArticle {

        private final DSLContext dsl;

        public Store(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Article apply(Article article) {
            Map<String, Object> a = Article.encoder().encode(article);
            String slug = (String) a.get("slug");
            String author = (String) ((Map<String, Object>) a.get("author")).get("username");

            int changed = dsl.update(table(name("articles")))
                    .set(field(name("title"), String.class), (String) a.get("title"))
                    .set(field(name("description"), String.class), (String) a.get("description"))
                    .set(field(name("body"), String.class), (String) a.get("body"))
                    .set(field(name("updated_at"), LocalDateTime.class),
                            LocalDateTime.parse((String) a.get("updatedAt")))
                    .where(field(name("slug"), String.class).eq(slug))
                    .execute();

            if (changed == 0) {
                dsl.insertInto(table(name("articles")))
                        .columns(field(name("slug"), String.class),
                                field(name("title"), String.class),
                                field(name("description"), String.class),
                                field(name("body"), String.class),
                                field(name("author"), String.class),
                                field(name("created_at"), LocalDateTime.class),
                                field(name("updated_at"), LocalDateTime.class))
                        .values(slug,
                                (String) a.get("title"),
                                (String) a.get("description"),
                                (String) a.get("body"),
                                author,
                                LocalDateTime.parse((String) a.get("createdAt")),
                                LocalDateTime.parse((String) a.get("updatedAt")))
                        .execute();
            }

            replaceTags(dsl, slug, (List<String>) a.get("tagList"));
            return article;
        }
    }

    /** readArticle: the article and its author, or the case that says there is no such slug. */
    public static final class Read extends ReadArticle {

        private final DSLContext dsl;

        public Read(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public ReadArticleResult apply(Slug slug) {
            Record row = articleRow(dsl, Slug.encoder().encode(slug));
            return row == null
                    ? ArticleNotFound()
                    : decodeOrThrow(Article.decoder().decode(articleMap(dsl, row, true), Path.ROOT));
        }
    }

    /** removeArticle: the article, its tags and its favorites all go. */
    public static final class Remove extends RemoveArticle {

        private final DSLContext dsl;

        public Remove(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Removed apply(Article article) {
            String slug = Slug.encoder().encode(article.slug());
            dsl.deleteFrom(table(name("article_tags")))
                    .where(field(name("slug"), String.class).eq(slug)).execute();
            dsl.deleteFrom(table(name("favorites")))
                    .where(field(name("slug"), String.class).eq(slug)).execute();
            dsl.deleteFrom(table(name("articles")))
                    .where(field(name("slug"), String.class).eq(slug)).execute();
            return Removed();
        }
    }

    /** readFavorited: which slugs this viewer has favorited, for a whole page at once. */
    public static final class ReadFavoritedSlugs extends ReadFavorited {

        private final DSLContext dsl;

        public ReadFavoritedSlugs(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FavoritedSlugs apply(Username viewer) {
            return favoritedBy(dsl, Username.encoder().encode(viewer));
        }
    }

    /** readFavoriteCounts: how many favorites each of a page's articles has, in one query. */
    public static final class ReadCounts extends ReadFavoriteCounts {

        private final DSLContext dsl;

        public ReadCounts(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FavoriteCounts apply(List<Slug> slugs) {
            Map<String, Object> counts = new LinkedHashMap<>();
            if (!slugs.isEmpty()) {
                List<String> wanted = slugs.stream().map(Slug.encoder()::encode).toList();
                dsl.select(field(name("slug"), String.class), org.jooq.impl.DSL.count())
                        .from(table(name("favorites")))
                        .where(field(name("slug"), String.class).in(wanted))
                        .groupBy(field(name("slug"), String.class))
                        .fetch()
                        .forEach(r -> counts.put(r.get(0, String.class), r.get(1, Integer.class)));
            }
            // A slug nobody favorited has no row, and the domain's map answers for every slug asked
            // about rather than leaving the boundary to guess what a missing key meant.
            for (Slug slug : slugs) {
                counts.putIfAbsent(Slug.encoder().encode(slug), 0);
            }
            return decodeOrThrow(FavoriteCounts.decoder()
                    .decode(Map.of("counts", counts), Path.ROOT));
        }
    }

    /**
     * readArticles: a page of summaries and how many the query matched. The body column is not
     * selected — that is the whole reason ArticleSummary sits beside Article — and the total is a
     * second query rather than the size of the page, because a client paging through needs the first.
     *
     * <p>The query is a sum, so the two shapes are folded rather than tested for. Nothing here has to
     * ask whether a feed also carried a tag: a FeedQuery cannot.
     */
    public static final class ReadPage extends ReadArticles {

        private final DSLContext dsl;

        public ReadPage(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public ArticlePage apply(ArticleQuery query) {
            Condition where = switch (query) {
                case GlobalQuery global -> globalCondition(global);
                case FeedQuery feed -> feedCondition(feed);
            };
            // A data constructor is not public, so the paging is read out of whichever case carries
            // it rather than rebuilt into a Paging here.
            long limit = switch (query) {
                case GlobalQuery global -> global.limit().value();
                case FeedQuery feed -> feed.limit().value();
            };
            long offset = switch (query) {
                case GlobalQuery global -> global.offset().value();
                case FeedQuery feed -> feed.offset().value();
            };

            List<Map<String, Object>> articles = selectArticles(dsl)
                    .where(where)
                    .orderBy(field(name("a", "created_at")).desc(), field(name("a", "slug")).asc())
                    .limit((int) limit)
                    .offset((int) offset)
                    .fetch()
                    .map(row -> articleMap(dsl, row, false));

            int total = dsl.fetchCount(
                    dsl.selectFrom(table(name("articles")).as("a")).where(where));

            return decodeOrThrow(ArticlePage.decoder()
                    .decode(Map.of("articles", articles, "total", total), Path.ROOT));
        }

        private static Condition globalCondition(GlobalQuery q) {
            Condition where = org.jooq.impl.DSL.noCondition();
            String tag = orNull(q.tag(), Tag.encoder()::encode);
            if (tag != null) {
                where = where.and(field(name("a", "slug"), String.class).in(
                        select(field(name("slug"), String.class)).from(table(name("article_tags")))
                                .where(field(name("tag"), String.class).eq(tag))));
            }
            String author = orNull(q.author(), Username.encoder()::encode);
            if (author != null) {
                where = where.and(field(name("a", "author"), String.class).eq(author));
            }
            String favoritedBy = orNull(q.favoritedBy(), Username.encoder()::encode);
            if (favoritedBy != null) {
                where = where.and(field(name("a", "slug"), String.class).in(
                        select(field(name("slug"), String.class)).from(table(name("favorites")))
                                .where(field(name("username"), String.class).eq(favoritedBy))));
            }
            return where;
        }

        /** A feed of nobody is empty rather than everything: following nothing shows you nothing. */
        private static Condition feedCondition(FeedQuery q) {
            List<String> authors = q.followees().stream().map(Username.encoder()::encode).toList();
            return authors.isEmpty()
                    ? org.jooq.impl.DSL.falseCondition()
                    : field(name("a", "author"), String.class).in(authors);
        }
    }

    /** storeFavorite: favoriting twice is favoriting once, so the row is removed before it is written. */
    public static final class Favorite extends StoreFavorite {

        private final DSLContext dsl;

        public Favorite(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FavoritedSlugs apply(Username viewer, Slug slug) {
            String who = Username.encoder().encode(viewer);
            String what = Slug.encoder().encode(slug);
            deleteFavorite(dsl, who, what);
            dsl.insertInto(table(name("favorites")))
                    .columns(field(name("username"), String.class), field(name("slug"), String.class))
                    .values(who, what)
                    .execute();
            return favoritedBy(dsl, who);
        }
    }

    /** storeUnfavorite: unfavoriting what you never favorited removes no row and is not an error. */
    public static final class Unfavorite extends StoreUnfavorite {

        private final DSLContext dsl;

        public Unfavorite(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FavoritedSlugs apply(Username viewer, Slug slug) {
            String who = Username.encoder().encode(viewer);
            deleteFavorite(dsl, who, Slug.encoder().encode(slug));
            return favoritedBy(dsl, who);
        }
    }

    /** readTags: every tag any article carries, once each. */
    public static final class ReadAllTags extends ReadTags {

        private final DSLContext dsl;

        public ReadAllTags(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public TagList apply() {
            List<String> tags = dsl.selectDistinct(field(name("tag"), String.class))
                    .from(table(name("article_tags")))
                    .orderBy(field(name("tag"), String.class))
                    .fetch(field(name("tag"), String.class));
            return decodeOrThrow(TagList.decoder()
                    .decode(Map.of("tags", List.copyOf(tags)), Path.ROOT));
        }
    }

    private static void deleteFavorite(DSLContext dsl, String username, String slug) {
        dsl.deleteFrom(table(name("favorites")))
                .where(field(name("username"), String.class).eq(username))
                .and(field(name("slug"), String.class).eq(slug))
                .execute();
    }

    private static FavoritedSlugs favoritedBy(DSLContext dsl, String username) {
        List<String> slugs = dsl.select(field(name("slug"), String.class))
                .from(table(name("favorites")))
                .where(field(name("username"), String.class).eq(username))
                .fetch(field(name("slug"), String.class));
        return decodeOrThrow(FavoritedSlugs.decoder()
                .decode(Map.of("slugs", List.copyOf(slugs)), Path.ROOT));
    }

    private static <T> String orNull(Option<T> option, java.util.function.Function<T, String> encode) {
        return option instanceof Option.Some<T> some ? encode.apply(some.value()) : null;
    }

    // --- the shared reads ---

    static Record articleRow(DSLContext dsl, String slug) {
        return selectArticles(dsl)
                .where(field(name("a", "slug"), String.class).eq(slug))
                .fetchOne();
    }

    static org.jooq.SelectOnConditionStep<? extends Record> selectArticles(DSLContext dsl) {
        return dsl.select(field(name("a", "slug"), String.class).as("slug"),
                        field(name("a", "title"), String.class).as("title"),
                        field(name("a", "description"), String.class).as("description"),
                        field(name("a", "body"), String.class).as("body"),
                        field(name("a", "created_at"), LocalDateTime.class).as("created_at"),
                        field(name("a", "updated_at"), LocalDateTime.class).as("updated_at"),
                        field(name("u", "username"), String.class).as("author_username"),
                        field(name("u", "bio"), String.class).as("author_bio"),
                        field(name("u", "image"), String.class).as("author_image"))
                .from(table(name("articles")).as("a"))
                .join(table(name("users")).as("u"))
                .on(field(name("a", "author"), String.class)
                        .eq(field(name("u", "username"), String.class)));
    }

    /** A row as the neutral map the derived decoder reads, with the author nested as a Profile. */
    static Map<String, Object> articleMap(DSLContext dsl, Record row, boolean withBody) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("username", row.get("author_username", String.class));
        putIfPresent(author, "bio", row.get("author_bio", String.class));
        putIfPresent(author, "image", row.get("author_image", String.class));

        String slug = row.get("slug", String.class);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("slug", slug);
        raw.put("title", row.get("title", String.class));
        raw.put("description", row.get("description", String.class));
        raw.put("tagList", tagsOf(dsl, slug));
        raw.put("author", author);
        raw.put("createdAt", row.get("created_at", LocalDateTime.class).toString());
        raw.put("updatedAt", row.get("updated_at", LocalDateTime.class).toString());
        if (withBody) {
            raw.put("body", row.get("body", String.class));
        }
        return raw;
    }

    static List<String> tagsOf(DSLContext dsl, String slug) {
        return dsl.select(field(name("tag"), String.class))
                .from(table(name("article_tags")))
                .where(field(name("slug"), String.class).eq(slug))
                .orderBy(field(name("tag"), String.class))
                .fetch(field(name("tag"), String.class));
    }

    private static void replaceTags(DSLContext dsl, String slug, List<String> tags) {
        dsl.deleteFrom(table(name("article_tags")))
                .where(field(name("slug"), String.class).eq(slug))
                .execute();
        for (String tag : tags) {
            dsl.insertInto(table(name("article_tags")))
                    .columns(field(name("slug"), String.class), field(name("tag"), String.class))
                    .values(slug, tag)
                    .execute();
        }
    }

    private static void putIfPresent(Map<String, Object> raw, String key, String value) {
        if (value != null) {
            raw.put(key, value);
        }
    }

    static <T> T decodeOrThrow(Result<T> result) {
        return switch (result) {
            case Ok<T> ok -> ok.value();
            case Err<T> err -> throw new IllegalStateException(
                    "a stored row no longer meets the domain's invariants: " + err.issues().asList());
        };
    }
}
