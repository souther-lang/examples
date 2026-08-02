package app.ordering;

import example.tax.RateChange;
import example.tax.RateSchedule;
import example.tax.ScheduleOf;
import example.tax.TaxCategory;

import org.jooq.DSLContext;
import org.jooq.Record2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * {@code scheduleOf}: every rate ever in force for a category, read from {@code tax_rates}. This is
 * the only place in the project where a NUMERIC column becomes a domain value — everything else in
 * the schema is INT (yen), DATE or VARCHAR.
 *
 * <p>What changed when the rate grew a date: the table stopped being one row per category and became
 * the history, and this reads all of it. Which row applies is not decided here — the domain decides
 * that against the date of the transaction, and a boundary that picked "the current one" would be
 * answering a question the domain never asked.
 *
 * <p>The rows come back ordered by {@code effective_from}, which is what {@code RateSchedule}'s
 * invariant demands; the invariant runs anyway when the schedule is built, so a table somebody
 * reordered is rejected here rather than silently mis-read. The category is turned into its stored key
 * through its own encoder, so the name written in the table is the case name the derived codec uses
 * and not a second spelling maintained by hand.
 *
 * <p>The values are built through the protected factories the behavior inherits (it declares
 * {@code constructs RateSchedule, RateChange, TaxRate}), which run the newtypes' invariants. A rate
 * outside 0..1 is not a business case — nothing in the domain can act on it — so it aborts, and the
 * boundary maps that to 500 like any other platform failure. A category with no rows at all is the
 * same kind of thing: every category has had a rate at some point, and one with none is a broken
 * table rather than an outcome.
 */
public final class JooqScheduleOf extends ScheduleOf {

    private final DSLContext dsl;

    public JooqScheduleOf(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public RateSchedule apply(TaxCategory category) {
        String key = (String) TaxCategory.encoder().encode(category);

        List<Record2<LocalDate, BigDecimal>> rows = dsl
                .select(field(name("effective_from"), LocalDate.class),
                        field(name("rate"), BigDecimal.class))
                .from(table(name("tax_rates")))
                .where(field(name("category"), String.class).eq(key))
                .orderBy(field(name("effective_from")))
                .fetch();

        if (rows.isEmpty()) {
            throw new IllegalStateException("no tax rate ever configured for " + key);
        }

        List<RateChange> changes = new ArrayList<>(rows.size());
        for (Record2<LocalDate, BigDecimal> row : rows) {
            changes.add(RateChange(row.value1(), TaxRate(row.value2())));
        }
        return RateSchedule(changes);
    }
}
