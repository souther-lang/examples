// Turning articles into what the spec's clients read.
//
// Every article in a response carries three facts that are about the request rather than about the
// article: whether this viewer favorited it, whether this viewer follows its author, and how many
// people favorited it. The first two are the viewer's, the third is the article's, and all three are
// read once for the whole response — one query each, however many articles came back.
package app.realworld.web;

import example.articles.Article;
import example.articles.ArticleSummary;
import example.articles.FavoriteCounts;
import example.articles.FavoritedSlugs;
import example.articles.ReadFavoriteCounts;
import example.articles.ReadFavorited;
import example.articles.Slug;
import example.identity.Username;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArticleViews {

    private final ReadFavorited readFavorited;
    private final ReadFavoriteCounts readFavoriteCounts;
    private final Following following;

    public ArticleViews(ReadFavorited readFavorited,
                        ReadFavoriteCounts readFavoriteCounts,
                        Following following) {
        this.readFavorited = readFavorited;
        this.readFavoriteCounts = readFavoriteCounts;
        this.following = following;
    }

    /** One article, enveloped as the spec states it. */
    public Map<String, Object> one(Article article, Viewer viewer) {
        Set<Slug> favorited = favoritedBy(viewer);
        Map<Slug, Integer> counts = countsFor(List.of(article.slug()));
        Set<Username> followees = following.of(viewer);

        return ConduitJson.envelope("article", ConduitJson.article(
                Article.encoder().encode(article),
                favorited.contains(article.slug()),
                counts.getOrDefault(article.slug(), 0),
                followees.contains(article.author().username())));
    }

    /**
     * A page of summaries, with the total the query matched rather than the number on this page. The
     * three reads happen once here and are then asked about per row.
     */
    public Map<String, Object> page(List<ArticleSummary> articles, int total, Viewer viewer) {
        Set<Slug> favorited = favoritedBy(viewer);
        Map<Slug, Integer> counts = countsFor(articles.stream().map(ArticleSummary::slug).toList());
        Set<Username> followees = following.of(viewer);

        List<Map<String, Object>> body = articles.stream()
                .map(summary -> ConduitJson.article(
                        ArticleSummary.encoder().encode(summary),
                        favorited.contains(summary.slug()),
                        counts.getOrDefault(summary.slug(), 0),
                        followees.contains(summary.author().username())))
                .toList();

        return Map.of("articles", body, "articlesCount", total);
    }

    private Set<Slug> favoritedBy(Viewer viewer) {
        return viewer.username()
                .map(readFavorited::apply)
                .map(FavoritedSlugs::slugs)
                .orElseGet(Set::of);
    }

    private Map<Slug, Integer> countsFor(List<Slug> slugs) {
        Map<Slug, Long> counts = readFavoriteCounts.apply(slugs).counts();
        return counts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().intValue()));
    }
}
