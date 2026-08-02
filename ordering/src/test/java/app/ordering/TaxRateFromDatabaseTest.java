package app.ordering;

import example.cart.PricedCart;
import example.tax.AllocateTax;
import example.tax.NoRateYet;
import example.tax.TaxAllocation;
import example.tax.TaxBreakdown;
import example.tax.TaxCategory;
import example.tax.TaxFor;
import example.tax.TaxRate;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The tax rates come from H2, through jOOQ, as the schema's one NUMERIC column and the DATE beside it
 * — the only place in this project where a database value becomes a {@code Decimal} rather than an
 * {@code Int}, a {@code String} or a {@code Date}. Boot starts for real ({@code @SpringBootTest}) and
 * the bound {@code taxFor} runs against the seeded rows.
 *
 * <p>What the table holds is the history, not the current figure, so the same seeded rows answer
 * differently depending on the date the question carries. The arithmetic itself is fixed by tax.sou's
 * {@code example}s with the lookup faked; what is checked here is the path the fake stands in for.
 */
@SpringBootTest
class TaxRateFromDatabaseTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-07-25");

    @Autowired TaxFor taxFor;
    @Autowired AllocateTax allocateTax;
    @Autowired DSLContext dsl;

    /** Puts the seeded history back: the four revisions schema.sql states and nothing a test added. */
    @AfterEach
    void restoreRates() {
        dsl.deleteFrom(table(name("tax_rates")))
                .where(field(name("category"), String.class).eq("StandardRate"))
                .and(field(name("effective_from"), LocalDate.class).notIn(
                        LocalDate.parse("1989-04-01"), LocalDate.parse("1997-04-01"),
                        LocalDate.parse("2014-04-01"), LocalDate.parse("2019-10-01")))
                .execute();
        setRate("StandardRate", "2019-10-01", "0.100");
    }

    private void setRate(String category, String from, String rate) {
        dsl.mergeInto(table(name("tax_rates")))
                .columns(field(name("category")), field(name("effective_from")), field(name("rate")))
                .key(field(name("category")), field(name("effective_from")))
                .values(category, LocalDate.parse(from), new BigDecimal(rate))
                .execute();
    }

    /** A category is decoded, not constructed: nothing outside the generated code can build one.
     *  Every case is a unit data, so it decodes from the case's name. */
    private TaxCategory category(String name) {
        return ok(TaxCategory.decoder().decode(name, Path.ROOT));
    }

    private <T> T ok(Result<T> r) {
        if (r instanceof Err<T> e) {
            throw new AssertionError("should decode: " + e.issues().asList());
        }
        return ((Ok<T>) r).value();
    }

    private PricedCart cart(long total) {
        return ok(PricedCart.decoder().decode(Map.of(
                "items", List.of(Map.of("sku", "apple", "quantity", 3L, "unitPrice", 105L, "weightGrams", 120L)),
                "total", total,
                "highValue", false), Path.ROOT));
    }

    private TaxBreakdown breakdown(long total, String category, LocalDate on) {
        return assertInstanceOf(TaxBreakdown.class, taxFor.apply(cart(total), category(category), on));
    }

    @Test
    void theRateIsReadFromTheNumericColumn() {
        TaxBreakdown standard = breakdown(315L, "StandardRate", TODAY);
        assertEquals(0, new BigDecimal("0.100").compareTo(standard.rate().value()));
        assertEquals(31L, standard.tax(), "315 × 0.100 = 31.5, dropped to 31");
        assertEquals(346L, standard.gross());

        TaxBreakdown reduced = breakdown(1100L, "ReducedRate", TODAY);
        assertEquals(0, new BigDecimal("0.080").compareTo(reduced.rate().value()));
        assertEquals(88L, reduced.tax());
    }

    /** The history is what the table is for: the rows stay the same and the day decides which one
     *  applies. October 2019 raised the standard rate, so a line dated the day before is at eight per
     *  cent and one dated the day itself is at ten. */
    @Test
    void theDateOfTheTransactionPicksTheRate() {
        assertEquals(25L, breakdown(315L, "StandardRate", LocalDate.parse("2019-09-30")).tax());
        assertEquals(31L, breakdown(315L, "StandardRate", LocalDate.parse("2019-10-01")).tax());
        assertEquals(9L, breakdown(315L, "StandardRate", LocalDate.parse("1996-12-31")).tax(),
                "three per cent, which is what it was until April 1997");
        assertEquals(15L, breakdown(315L, "StandardRate", LocalDate.parse("1997-04-01")).tax(),
                "and five from that day");
    }

    /** The reduced rate was introduced in October 2019, so its history starts there — and a date
     *  before it is not a zero rate and not a missing row, but the case that says the rate did not
     *  exist yet. */
    @Test
    void aDateBeforeACategoryExistedIsAnOutcome() {
        Object answer = taxFor.apply(cart(1100L), category("ReducedRate"),
                LocalDate.parse("2019-09-30"));

        NoRateYet none = assertInstanceOf(NoRateYet.class, answer);
        assertEquals(LocalDate.parse("2019-09-30"), none.on());
    }

    @Test
    void aRevisionAddsARowRatherThanOverwritingOne() {
        // The figure is administered outside the domain, and a revision is a new row with the day it
        // starts — the old rate stays true of the days it covered.
        setRate("StandardRate", "2027-04-01", "0.120");

        assertEquals(31L, breakdown(315L, "StandardRate", LocalDate.parse("2027-03-31")).tax());
        assertEquals(37L, breakdown(315L, "StandardRate", LocalDate.parse("2027-04-01")).tax(),
                "315 × 0.120 = 37.8, dropped to 37");
    }

    @Test
    void aRowOutsideTheRateRangeAborts() {
        // The invariant runs where the value is built, so a bad row cannot enter the domain. It is
        // not a business case — nothing could act on it — so it aborts rather than returning a case.
        setRate("StandardRate", "2020-04-01", "1.500");

        assertThrows(souther.runtime.ConstraintViolation.class,
                () -> taxFor.apply(cart(315L), category("StandardRate"), TODAY));
    }

    /** The rate a row carries is written for the invoice inside the domain, not reassembled at the
     *  boundary: 0.100 from the NUMERIC column becomes the string "10%". */
    @Test
    void theRateIsWrittenAsAPercentage() {
        assertEquals("10%", breakdown(315L, "StandardRate", TODAY).rateLabel());
        assertEquals("8%", breakdown(1100L, "ReducedRate", TODAY).rateLabel());
    }

    @Test
    void theDecimalRateStillRoundTripsThroughTheCodec() {
        TaxRate rate = breakdown(315L, "StandardRate", TODAY).rate();
        assertEquals(0, new BigDecimal("0.100").compareTo(TaxRate.encoder().encode(rate)));
    }

    /** The same schedule read as a per-line breakdown. The lines add up to the figure the invoice
     *  states, which is a rule of the type rather than of this assertion — an allocation that lost the
     *  yen could not have been built to be returned at all. */
    @Test
    void theLinesOfAnAllocationAddUpToTheStatedTotal() {
        PricedCart threeLines = ok(PricedCart.decoder().decode(Map.of(
                "items", List.of(
                        Map.of("sku", "pen", "quantity", 1L, "unitPrice", 105L, "weightGrams", 120L),
                        Map.of("sku", "note", "quantity", 1L, "unitPrice", 105L, "weightGrams", 120L),
                        Map.of("sku", "clip", "quantity", 1L, "unitPrice", 105L, "weightGrams", 120L)),
                "total", 315L,
                "highValue", false), Path.ROOT));

        TaxAllocation allocation = assertInstanceOf(TaxAllocation.class,
                allocateTax.apply(threeLines, category("StandardRate"), TODAY));

        assertEquals(31L, allocation.taxTotal());
        assertEquals(List.of(10L, 10L, 11L),
                allocation.lines().stream().map(example.tax.LineTax::tax).toList());
    }
}
