package app.ordering.web;

import app.ordering.JooqScheduleOf;
import app.ordering.JooqRecordOrder;
import app.ordering.JooqReserveStock;

import example.cart.Cart;
import example.cart.PricedCart;
import example.cart.Quote;
import example.ordering.Place;
import example.ordering.RecordOrder;
import example.ordering.ReserveStock;
import example.shipping.PlanPicks;
import example.tax.AllocateTax;
import example.tax.ScheduleOf;
import example.tax.TaxFor;


import org.jooq.DSLContext;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the Souther generated code from both modules to Spring. DataSource / DSLContext /
 * TransactionManager and schema.sql execution are all left to Spring Boot autoconfig
 * ({@code spring-boot-starter-jooq} + H2). What is added here is the generated-side beans (cart's
 * pure quote, ordering's injected implementations, and the bound place pipeline) and the programmatic
 * transaction tool ({@link TransactionTemplate}).
 *
 * <p>The autoconfig DSLContext goes through a {@code TransactionAwareDataSourceProxy}, so every jOOQ
 * statement uses the connection Spring bound to the thread's transaction. That is why the recorded
 * rows (recordOrder) and the stock decrements (reserveStock) share one transaction and roll back
 * together.
 */
@Configuration(proxyBeanMethods = false)
public class OrderingConfig {

    /**
     * Turns off jOOQ identifier quoting. JooqAutoConfiguration picks up this Settings bean. Unquoted
     * names are folded to upper case by H2, so the lower-case names in code (orders / order_lines /
     * stock / qty) match the schema's ORDERS / ORDER_LINES / STOCK / QTY.
     */
    @Bean
    public Settings jooqSettings() {
        return new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    /** cart's pricing behavior. It is pure (no requires), so it needs no injection. */
    @Bean
    public Quote quote() {
        return Quote.of();
    }

    // --- the injected outside-world implementations (DSLContext is injected by autoconfig) ---
    @Bean
    public RecordOrder recordOrder(DSLContext dsl) {
        return new JooqRecordOrder(dsl);
    }

    @Bean
    public ReserveStock reserveStock(DSLContext dsl) {
        return new JooqReserveStock(dsl);
    }

    /**
     * The bound pipeline place. It requires {@code {recordOrder, reserveStock}}, supplied here through
     * {@code Place.bind} (spec 19.5). Its input is cart's PricedCart — the value the boundary hands it
     * after quote prices the cart.
     */
    @Bean
    public Place place(RecordOrder recordOrder, ReserveStock reserveStock) {
        return Place.bind(recordOrder, reserveStock);
    }

    /**
     * shipping's pick planning. Pure, like quote — it reads a shelf map the boundary hands it rather
     * than a warehouse, so nothing is injected. It is the deepest module in this project (shipping ->
     * inventory -> cart) and needs no more wiring than the shallowest.
     */
    @Bean
    public PlanPicks planPicks() {
        return PlanPicks.of();
    }

    /** tax's injected rate lookup: every rate ever in force for a category, out of the one NUMERIC
     *  column in the schema and the DATE beside it (example.tax). */
    @Bean
    public ScheduleOf scheduleOf(DSLContext dsl) {
        return new JooqScheduleOf(dsl);
    }

    /** The tax calculation, bound to that lookup. Pure arithmetic once the schedule is in hand — the
     *  date of the transaction picks the rate out of it, inside the domain. */
    @Bean
    public TaxFor taxFor(ScheduleOf scheduleOf) {
        return TaxFor.bind(scheduleOf);
    }

    /** The same schedule read a second way: the tax total shared out over the lines, adding up to
     *  what {@code taxFor} states. */
    @Bean
    public AllocateTax allocateTax(ScheduleOf scheduleOf) {
        return AllocateTax.bind(scheduleOf);
    }
}
