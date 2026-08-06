package blog.articles;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the derived codecs make of the article types, which the {@code example} rows in articles.sou
 * cannot show: they run behaviors, not encoders.
 *
 * <p>The load-bearing one is that an {@code ArticleSummary} encodes without a body. The listing
 * endpoints answer summaries because their SQL does not select the body column, and the spec's list
 * entries carry no {@code body} field — one fact, and this is where the two meet.
 */
class ArticleTest {

    private static final Map<String, Object> AUTHOR = Map.of("username", "jake");

    @Test
    void anArticleCarriesItsBodyAndASummaryDoesNot() {
        Map<String, Object> article = Article.encoder().encode(ok(Article.decoder().decode(full(), Path.ROOT)));
        Map<String, Object> summary = ArticleSummary.encoder()
                .encode(ok(ArticleSummary.decoder().decode(full(), Path.ROOT)));

        assertTrue(article.containsKey("body"));
        assertFalse(summary.containsKey("body"), "a list entry carries no body");
    }

    @Test
    void anAuthorArrivesAsANestedProfileRatherThanAName() {
        Map<String, Object> encoded =
                Article.encoder().encode(ok(Article.decoder().decode(full(), Path.ROOT)));

        assertInstanceOf(Map.class, encoded.get("author"));
        assertEquals("jake", ((Map<?, ?>) encoded.get("author")).get("username"));
    }

    @Test
    void anAbsentBioIsLeftOutOfTheEncodedAuthorRatherThanWrittenAsNull() {
        Map<String, Object> encoded =
                Article.encoder().encode(ok(Article.decoder().decode(full(), Path.ROOT)));

        // The spec writes "bio": null, so ConduitJson puts the key back on the way out. This is the
        // behaviour it exists to correct.
        assertFalse(((Map<?, ?>) encoded.get("author")).containsKey("bio"));
    }

    @Test
    void aSlugWithSpacesInItIsNotASlug() {
        assertInstanceOf(Err.class, Slug.decoder().decode("how to train", Path.ROOT));
    }

    @Test
    void anEmptyTagIsRefused() {
        assertInstanceOf(Err.class, Tag.decoder().decode("", Path.ROOT));
    }

    private static Map<String, Object> full() {
        return Map.of(
                "slug", "how-to-train-your-dragon",
                "title", "How to train your dragon",
                "description", "Ever wonder how?",
                "tagList", List.of("dragons", "training"),
                "author", AUTHOR,
                "createdAt", "2026-08-06T03:22:56",
                "updatedAt", "2026-08-06T03:22:56",
                "body", "It takes a Jacobian");
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
