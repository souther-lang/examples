package app.realworld;

import blog.articles.Slug;
import blog.articles.SlugExists;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import souther.compiler.examples.BoundExamples;
import souther.compiler.examples.RecordedRow;
import souther.compiler.examples.RowEvaluation;
import souther.compiler.examples.SoutherExamples;
import souther.compiler.examples.StandinEntry;
import souther.compiler.examples.StandinObservation;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two texts about {@code slugExists} and one implementation, held to both at once.
 *
 * <p>{@code fake slugExists} was written so that {@code createArticle}'s rows have something to
 * dispatch to. It is a statement about the real dependency all the same — that
 * {@code how-to-train-your-dragon} names no article and {@code taken} does — and until the SQL was
 * bound nothing ever checked it. ADR-0093 compares a fake with its behavior's recorded rows, and
 * {@code slugExists} had none for that comparison to reach.
 *
 * <p>So the rows in articles.sou state the same two inputs, and the two ways of asking are run here
 * side by side: {@code evaluate} adjudicates what the behavior owes and answers {@code FAILED} when
 * it is not met, {@code observe} relates two answers and answers with the observation. They are
 * spelled apart because they decide different things, and {@link StandinEntry#alsoBy()} is what ties
 * one to the other — the recorded rows stating an entry's input.
 *
 * <p>The correlation only means something under one world, and no API can know that two of its calls
 * saw one. That is why the world is arranged here, once, and neither call is allowed to move it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlugExistsDifferentialTest {

    private static final List<Path> MODEL = List.of(
            Path.of("src/main/souther/articles.sou"),
            Path.of("src/main/souther/identity.sou"),
            Path.of("src/main/souther/comments.sou"));

    private static final String FREE = "how-to-train-your-dragon";
    private static final String TAKEN = "taken";

    @Autowired DSLContext dsl;

    /**
     * The world both texts describe. The title is deliberately not the slug, so an implementation
     * reading the wrong column is a different answer rather than the same one by luck.
     */
    @BeforeEach
    void theWorldBothTextsDescribe() {
        dsl.deleteFrom(table(name("favorites"))).execute();
        dsl.deleteFrom(table(name("article_tags"))).execute();
        dsl.deleteFrom(table(name("articles"))).execute();
        LocalDateTime when = LocalDateTime.parse("2026-08-06T01:00:00");
        dsl.insertInto(table(name("articles")))
                .columns(field(name("slug"), String.class), field(name("title"), String.class),
                        field(name("description"), String.class), field(name("body"), String.class),
                        field(name("author"), String.class),
                        field(name("created_at"), LocalDateTime.class),
                        field(name("updated_at"), LocalDateTime.class))
                .values(TAKEN, "Something else entirely", "d", "b", "jake", when, when)
                .execute();
    }

    /** What the behavior owes, held to the SQL that answers it. */
    @TestFactory
    Stream<DynamicTest> theSqlAnswersEveryRowSlugExistsOwes() {
        BoundExamples bound = SoutherExamples.of(MODEL).bind(new JooqArticles.SlugIsTaken(dsl));
        return bound.rows().stream().map(row -> DynamicTest.dynamicTest(row.shown(), () -> {
            RowEvaluation evaluated = bound.evaluate(row);
            assertTrue(evaluated.held(), evaluated.shown(Locale.ENGLISH));
        }));
    }

    /**
     * What stands in for the behavior elsewhere, held to the same SQL. Neither entry is a row of
     * {@code slugExists}: they were written for {@code createArticle}, and running them is evidence
     * about the SQL that costs nothing to obtain.
     */
    @TestFactory
    Stream<DynamicTest> theSqlAgreesWithTheTableThatStandsInForIt() {
        BoundExamples bound = SoutherExamples.of(MODEL).bind(new JooqArticles.SlugIsTaken(dsl));
        List<StandinEntry> entries = bound.standinEntries();
        assertEquals(2, entries.size(), "the `_` row states no input and is not one of them");
        return entries.stream().map(entry -> DynamicTest.dynamicTest(
                entry.shownInputs() + " states " + entry.shownStated(), () -> {
                    assertInstanceOf(StandinObservation.AsStated.class, bound.observe(entry),
                            "the fake and the SQL disagree about " + entry.shownInputs());
                    assertFalse(entry.alsoBy().isEmpty(),
                            "a recorded row states this input, so a disagreement could be apportioned");
                }));
    }

    /**
     * The effect. {@link WrongColumn} is the SQL with {@code slug} read as {@code title}, which is
     * the mistake a query mapping two columns the wrong way round makes and which reads correctly.
     *
     * <p>Both ways of asking move together and neither on its own says why: the row does not hold,
     * the entry stating the same input is not as stated, and the two texts still agree with each
     * other. That is the reading — where two texts written apart agree and the implementation is
     * alone in disagreeing, the implementation is what moved.
     *
     * <p>The other input is answered correctly by accident, which is the case for running both: no
     * article is titled {@code how-to-train-your-dragon} either, so reading the wrong column happens
     * to answer {@code false} there.
     */
    @Test
    void aColumnMixUpMovesTheRowAndTheStandinTogether() {
        BoundExamples bound = SoutherExamples.of(MODEL).bind(new WrongColumn(dsl));

        StandinEntry taken = entryFor(bound, TAKEN);
        StandinObservation observed = bound.observe(taken);
        assertInstanceOf(StandinObservation.OtherThanStated.class, observed,
                "the fake said the slug was taken and the SQL did not notice");

        List<RecordedRow> alsoBy = taken.alsoBy();
        assertEquals(1, alsoBy.size(), "one recorded row states this entry's input");
        RowEvaluation row = bound.evaluate(alsoBy.get(0));
        assertFalse(row.held(), "the row and the stand-in did not move together");

        StandinEntry free = entryFor(bound, FREE);
        assertInstanceOf(StandinObservation.AsStated.class, bound.observe(free),
                "nothing is titled that either, so the wrong column answers this one by accident");
    }

    private static StandinEntry entryFor(BoundExamples bound, String slug) {
        String shown = "Slug(\"" + slug + "\")";
        return bound.standinEntries().stream()
                .filter(entry -> entry.shownInputs().equals(List.of(shown)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stand-in entry for " + shown));
    }

    /** SlugIsTaken with `slug` read as `title`. Nothing else differs. */
    static final class WrongColumn extends SlugExists {

        private final DSLContext dsl;

        WrongColumn(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Boolean apply(Slug slug) {
            return dsl.fetchExists(table(name("articles")),
                    field(name("title"), String.class).eq(Slug.encoder().encode(slug)));
        }
    }
}
