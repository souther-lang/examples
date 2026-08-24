package app.realworld;

import blog.articles.ArticlePage;
import blog.articles.ArticleQuery;
import blog.articles.FeedQuery;
import blog.articles.GlobalQuery;
import blog.articles.ReadArticles;
import blog.articles.Tag;
import blog.identity.Username;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import souther.compiler.examples.BoundExamples;
import souther.compiler.examples.ContractObservation;
import souther.compiler.examples.RecordedRow;
import souther.compiler.examples.RowEvaluation;
import souther.compiler.examples.SoutherExamples;
import souther.runtime.Option;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code example readArticles} rows in articles.sou, run against the jOOQ implementation that
 * answers them in production.
 *
 * <p>Every other test in this module asserts in Java what the implementation should return. These
 * do not: the expectations are in the model, beside the behavior they are about, and what is written
 * here is the world they are asked in and the loop that asks them. When something does not hold,
 * what is printed is the compiler's own sentence about it.
 *
 * <p>The rows are asked two questions. {@code evaluate} holds the answer to the page recorded beside
 * the behavior; {@code checkContract} holds it to what the behavior states of what it answers, and
 * to nothing the row records.
 *
 * <p>The source is read at the time the run happens rather than travelling with the classes, so a
 * model edited after the implementation was compiled is found out here. {@link #anOrForAnAndIsCaught}
 * is the other direction: an implementation edited under a model that did not move.
 */
// The same context the other tests in this module use. A plain `@SpringBootTest` is a second
// context cache key, and the second context runs schema.sql again against the same in-memory
// database.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadArticlesExamplesTest {

    /** articles.sou imports blog.identity, so the module it imports is read alongside it. */
    private static final List<Path> MODEL = List.of(
            Path.of("src/main/souther/articles.sou"),
            Path.of("src/main/souther/identity.sou"),
            Path.of("src/main/souther/comments.sou"));

    @Autowired DSLContext dsl;

    /**
     * The one world every row is true of. It is written here and not in the model because a row
     * carries its inputs and its expectation and nothing else: what changes between rows is the
     * caller's, and here nothing changes between them.
     */
    @BeforeEach
    void seedTheWorldTheRowsAreAskedIn() {
        dsl.deleteFrom(table(name("favorites"))).execute();
        dsl.deleteFrom(table(name("article_tags"))).execute();
        dsl.deleteFrom(table(name("articles"))).execute();
        dsl.deleteFrom(table(name("follows"))).execute();
        dsl.deleteFrom(table(name("comments"))).execute();
        dsl.deleteFrom(table(name("users"))).execute();

        user("jake");
        user("gerome");
        article("dragons-are-real", "Dragons are real", "They are", "jake",
                "2026-08-06T04:00:00", "dragons");
        article("how-to-train-your-dragon", "How to train your dragon", "Ever wonder how?", "jake",
                "2026-08-06T03:22:56", "dragons", "training");
        article("on-functional-programming", "On functional programming", "A monad is", "gerome",
                "2026-08-06T02:00:00", "fp");
        favorite("gerome", "how-to-train-your-dragon");
    }

    /**
     * One test per recorded row. The implementation bound is the one {@code RealWorldConfig} wires
     * into the running application, not a stand-in written to pass.
     */
    @TestFactory
    Stream<DynamicTest> theJooqImplementationAnswersEveryRowReadArticlesOwes() {
        BoundExamples bound = SoutherExamples.of(MODEL).bind(new JooqArticles.ReadPage(dsl));
        assertEquals(List.of("readArticles"), bound.boundBehaviors());
        return bound.rows().stream().map(row -> DynamicTest.dynamicTest(
                row.shown(), () -> assertHeld(bound, row)));
    }

    /**
     * The same ten inputs, decided by what the behavior states rather than by what a person wrote
     * out.
     *
     * <p>{@code evaluate} above asks whether the implementation answered the recorded page;
     * {@code checkContract} asks only whether the answer keeps the {@code ensures} clauses, and
     * nothing about the record is consulted. That is the question a contract test asks, and it is
     * the one that survives a world the rows were not recorded in — a shared database, a snapshot of
     * another size — where the written page is no longer the answer and the declaration still is.
     *
     * <p>{@code behaviorsWithContracts} is asserted rather than read: without it, a model edited to
     * drop the clauses would leave every row here answering {@code NothingStated}, and this factory
     * would go on being green while asking nothing.
     */
    @TestFactory
    Stream<DynamicTest> theJooqImplementationKeepsWhatReadArticlesStates() {
        BoundExamples bound = SoutherExamples.of(MODEL).bind(new JooqArticles.ReadPage(dsl));
        assertEquals(List.of("readArticles"), bound.behaviorsWithContracts(),
                "the clauses this factory is the whole of are no longer in the model");
        return bound.rows().stream().map(row -> DynamicTest.dynamicTest(
                row.shown(), () -> assertKeptWhatIsStated(bound, row)));
    }

    /**
     * The effect, pinned so it cannot quietly stop being true.
     *
     * <p>{@link OrForAnAnd} is {@code ReadPage} with one character changed: the filters are combined
     * with {@code or} instead of {@code and}, which is a query that reads correctly and returns the
     * union of what each filter matches. Seven of the ten rows still hold against it — a tag alone
     * and an author alone are unaffected, and so is a tag and an author that one article satisfies
     * together, because a union and an intersection agree there.
     *
     * <p>What catches it is the row where the two filters disagree. That is the row a person writing
     * assertions by hand does not think to write, because the query it describes matches nothing and
     * there is nothing to assert about an empty page.
     *
     * <p>The declaration catches it on that same row, and catches it without being shown the page
     * anybody wrote: what {@code ensures} says is that every article answered matches the query
     * asked, and a union holds articles that match one filter and not the other. So the row a person
     * would not have thought to write is one the model decides on its own — the inputs are still
     * somebody's, and the answer is not.
     */
    @TestFactory
    Stream<DynamicTest> anOrForAnAndIsCaught() {
        BoundExamples bound = SoutherExamples.of(MODEL).bind(new OrForAnAnd(dsl));
        String disagreeing = "two filters narrow together, so one nobody satisfies matches nothing";
        return Stream.of(
                DynamicTest.dynamicTest("the row where the two filters disagree does not hold", () -> {
                    RowEvaluation caught = bound.evaluate(named(bound, disagreeing));
                    assertFalse(caught.held(),
                            "an or for an and went unnoticed: " + caught.shown(Locale.ENGLISH));
                }),
                DynamicTest.dynamicTest("the declaration alone catches it, with no page written", () -> {
                    ContractObservation caught = bound.checkContract(named(bound, disagreeing));
                    ContractObservation.Broken broken = assertInstanceOf(
                            ContractObservation.Broken.class, caught,
                            "an or for an and kept what readArticles states: " + caught.shown());
                    assertFalse(broken.why().isBlank(), "a broken clause said nothing");
                }),
                DynamicTest.dynamicTest("a tag on its own cannot tell the two apart", () ->
                        assertHeld(bound, named(bound, "a tag narrows to the articles carrying it"))),
                DynamicTest.dynamicTest("nor can the declaration, on that row", () ->
                        assertKeptWhatIsStated(bound,
                                named(bound, "a tag narrows to the articles carrying it"))),
                DynamicTest.dynamicTest("two filters one article satisfies cannot either", () ->
                        assertHeld(bound, named(bound,
                                "two filters one article satisfies keeps that article"))));
    }

    private static void assertHeld(BoundExamples bound, RecordedRow row) {
        RowEvaluation evaluated = bound.evaluate(row);
        assertTrue(evaluated.held(), evaluated.shown(Locale.ENGLISH));
    }

    /**
     * Held to the declaration and to nothing the row records.
     *
     * <p>{@code NoClauseWasBroken} is the absence of a violation and not proof that a clause bore on
     * this answer: the rule says nothing about a page filtered only on {@code favoritedBy}, because
     * an ArticleSummary carries no favouriting, and that row arrives here having proved nothing.
     */
    private static void assertKeptWhatIsStated(BoundExamples bound, RecordedRow row) {
        ContractObservation observed = bound.checkContract(row);
        assertInstanceOf(ContractObservation.NoClauseWasBroken.class, observed, observed.shown());
    }

    /** `evaluate` takes the enumerated row, so a row named here is looked up among them. */
    private static RecordedRow named(BoundExamples bound, String name) {
        return bound.rows().stream()
                .filter(row -> row.shown().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row named `" + name + "`"));
    }

    /** ReadPage with `and` written as `or`. Nothing else differs. */
    static final class OrForAnAnd extends ReadArticles {

        private final DSLContext dsl;

        OrForAnAnd(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public ArticlePage apply(ArticleQuery query) {
            Condition where = switch (query) {
                case GlobalQuery global -> globalCondition(global);
                case FeedQuery feed -> feedCondition(feed);
            };
            long limit = switch (query) {
                case GlobalQuery global -> global.limit().value();
                case FeedQuery feed -> feed.limit().value();
            };
            long offset = switch (query) {
                case GlobalQuery global -> global.offset().value();
                case FeedQuery feed -> feed.offset().value();
            };
            List<Map<String, Object>> articles = JooqArticles.selectArticles(dsl)
                    .where(where)
                    .orderBy(field(name("a", "created_at")).desc(), field(name("a", "slug")).asc())
                    .limit((int) limit)
                    .offset((int) offset)
                    .fetch()
                    .map(row -> JooqArticles.articleMap(dsl, row, false));
            int total = dsl.fetchCount(
                    dsl.selectFrom(table(name("articles")).as("a")).where(where));
            return ArticlePage.decoder()
                    .decode(Map.of("articles", articles, "total", total)).getOrThrow();
        }

        private static Condition globalCondition(GlobalQuery q) {
            Condition where = org.jooq.impl.DSL.noCondition();
            String tag = orNull(q.tag(), Tag.encoder()::encode);
            if (tag != null) {
                where = where.or(field(name("a", "slug"), String.class).in(
                        select(field(name("slug"), String.class)).from(table(name("article_tags")))
                                .where(field(name("tag"), String.class).eq(tag))));
            }
            String author = orNull(q.author(), Username.encoder()::encode);
            if (author != null) {
                where = where.or(field(name("a", "author"), String.class).eq(author));
            }
            String favoritedBy = orNull(q.favoritedBy(), Username.encoder()::encode);
            if (favoritedBy != null) {
                where = where.or(field(name("a", "slug"), String.class).in(
                        select(field(name("slug"), String.class)).from(table(name("favorites")))
                                .where(field(name("username"), String.class).eq(favoritedBy))));
            }
            return where;
        }

        private static Condition feedCondition(FeedQuery q) {
            List<String> authors = q.followees().stream().map(Username.encoder()::encode).toList();
            return authors.isEmpty()
                    ? org.jooq.impl.DSL.falseCondition()
                    : field(name("a", "author"), String.class).in(authors);
        }

        private static <T> String orNull(Option<T> option, Function<T, String> encode) {
            return option instanceof Option.Some<T> some ? encode.apply(some.value()) : null;
        }
    }

    // --- seeding ---

    private void user(String username) {
        dsl.insertInto(table(name("users")))
                .columns(field(name("username"), String.class), field(name("email"), String.class),
                        field(name("password_hash"), String.class))
                .values(username, username + "@jake.jake", "x")
                .execute();
    }

    private void article(String slug, String title, String description, String author,
                         String at, String... tags) {
        LocalDateTime when = LocalDateTime.parse(at);
        dsl.insertInto(table(name("articles")))
                .columns(field(name("slug"), String.class), field(name("title"), String.class),
                        field(name("description"), String.class), field(name("body"), String.class),
                        field(name("author"), String.class),
                        field(name("created_at"), LocalDateTime.class),
                        field(name("updated_at"), LocalDateTime.class))
                .values(slug, title, description, "b", author, when, when)
                .execute();
        for (String tag : tags) {
            dsl.insertInto(table(name("article_tags")))
                    .columns(field(name("slug"), String.class), field(name("tag"), String.class))
                    .values(slug, tag)
                    .execute();
        }
    }

    private void favorite(String username, String slug) {
        dsl.insertInto(table(name("favorites")))
                .columns(field(name("username"), String.class), field(name("slug"), String.class))
                .values(username, slug)
                .execute();
    }
}
