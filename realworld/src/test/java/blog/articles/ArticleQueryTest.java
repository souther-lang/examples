package blog.articles;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A search is a value, so the bounds on a page are stated once — as invariants on {@code Limit} and
 * {@code Offset} — and the decoder is what enforces them. The point of these is that an unbounded or
 * nonsensical page is refused at the boundary and no SQL is ever shown one.
 */
class ArticleQueryTest {

    @Test
    void anOrdinaryPageDecodes() {
        assertInstanceOf(Ok.class, ArticleQuery.decoder().decode(global(20, 0), Path.ROOT));
    }

    @Test
    void aPageOfNothingIsRefused() {
        assertInstanceOf(Err.class, ArticleQuery.decoder().decode(global(0, 0), Path.ROOT));
    }

    @Test
    void aPageOfAThousandIsRefused() {
        assertInstanceOf(Err.class, ArticleQuery.decoder().decode(global(1000, 0), Path.ROOT));
    }

    @Test
    void aNegativeOffsetIsRefused() {
        assertInstanceOf(Err.class, ArticleQuery.decoder().decode(global(20, -1), Path.ROOT));
    }

    @Test
    void aFeedCarriesTheFolloweesAndNoFilters() {
        Map<String, Object> feed = Map.of(
                "type", "FeedQuery",
                "limit", 20,
                "offset", 0,
                "followees", List.of("jake", "gerome"));

        assertInstanceOf(Ok.class, ArticleQuery.decoder().decode(feed, Path.ROOT));
    }

    private static Map<String, Object> global(int limit, int offset) {
        return Map.of("type", "GlobalQuery", "limit", limit, "offset", offset);
    }
}
