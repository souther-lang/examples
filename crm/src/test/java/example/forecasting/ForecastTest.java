package example.forecasting;

import example.crm.ConversionRate;
import example.crm.CurrencyCode;
import example.crm.RateTable;
import example.crm.UserId;
import example.org.RoleName;
import example.org.RoleNode;
import example.pipeline.Prospecting;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forecast at the boundary.
 *
 * <p>The load-bearing check is the map key: {@code FiscalPeriod} is a value the domain assembles from a close
 * date and the org's fiscal-year start month, and it crosses the boundary as the string it was built as —
 * having been through the same invariant a key arriving from outside would face. A key the model builds and a
 * key the model reads are the same kind of thing, which is the point of building it in the model at all.
 *
 * <p>Money is asserted with {@code compareTo} throughout. An encoded {@code BigDecimal} carries the scale the
 * arithmetic produced, and {@code equals} on a {@code BigDecimal} compares scale as well as value, so
 * {@code assertEquals(new BigDecimal("2750"), …)} would fail against {@code 2750.00} for no reason the domain
 * cares about.
 */
class ForecastTest {

    @Test
    void thePeriodKeyTheDomainBuiltSurvivesTheBoundary() {
        Forecast forecast = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"),
                List.of(deal("006000000000001", "1000000.00", "2026-09-30"),
                        deal("006000000000002", "2000000.00", "2026-12-31")),
                1L, yenRates()));

        Map<String, Object> encoded = Forecast.encoder().encode(forecast);
        @SuppressWarnings("unchecked")
        Map<String, Object> byPeriod = (Map<String, Object>) encoded.get("byPeriod");
        assertTrue(byPeriod.containsKey("FY26-Q3"), byPeriod.toString());
        assertTrue(byPeriod.containsKey("FY26-Q4"), byPeriod.toString());

        // Ten per cent of a million, weighted at the first stage's probability.
        assertEquals(0, ((BigDecimal) byPeriod.get("FY26-Q3")).compareTo(new BigDecimal("100000")));
        assertEquals(0, ((BigDecimal) byPeriod.get("FY26-Q4")).compareTo(new BigDecimal("200000")));
        assertEquals(0, ((BigDecimal) encoded.get("total")).compareTo(new BigDecimal("300000")));
        assertEquals(2L, ((Number) encoded.get("dealCount")).longValue());
    }

    @Test
    void anAprilFiscalYearMovesTheSameDealToAnotherQuarter() {
        Forecast forecast = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000001", "1000000.00", "2026-09-30")), 4L, yenRates()));

        @SuppressWarnings("unchecked")
        Map<String, Object> byPeriod = (Map<String, Object>) Forecast.encoder().encode(forecast).get("byPeriod");
        assertEquals(List.of("FY26-Q2"), List.copyOf(byPeriod.keySet()));
    }

    @Test
    void aThirteenthMonthIsRefused() {
        assertInstanceOf(InvalidFiscalYearStart.class,
                WeightedForecast.of().apply(user("u-001"), List.of(), 13L, yenRates()));
    }

    @Test
    void aQuarterWithNoDealsHasNoForecastRatherThanZero() {
        Forecast forecast = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000001", "1000000.00", "2026-09-30")), 1L, yenRates()));

        assertInstanceOf(NoForecastForPeriod.class,
                QuotaGap.of().apply(forecast, quota("100000.00"), period("FY26-Q4")));
        assertInstanceOf(OnTrack.class,
                QuotaGap.of().apply(forecast, quota("100000.00"), period("FY26-Q3")));
    }

    @Test
    void theQuotaIsReadThroughAnInjectedLookupWithTwoInputs() {
        // The third injected shape in the project: two inputs, so the generated base is a standalone abstract
        // class rather than a single-input Behavior.
        AttainmentFor attainment = AttainmentFor.bind(new CompPlan());

        Forecast forecast = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000001", "1500000.00", "2026-09-30")), 1L, yenRates()));

        OnTrack onTrack = assertInstanceOf(OnTrack.class,
                attainment.apply(user("u-001"), forecast, period("FY26-Q3")));
        assertEquals(150, onTrack.attainment().value(), "150000 against a quota of 100000");

        // Another rep, another number, and the same forecast falls short of it.
        ShortOfQuota short_ = assertInstanceOf(ShortOfQuota.class,
                attainment.apply(user("u-002"), forecast, period("FY26-Q3")));
        assertEquals(0, ((BigDecimal) ShortOfQuota.encoder().encode(short_).get("gap"))
                .compareTo(new BigDecimal("350000")));
    }

    @Test
    void aManagersForecastIsTheTeamUnderThemSummedRatherThanOneOfThem() {
        // This is the assertion Map.union would have failed: both reps have a Q3, and a left-biased union
        // would have kept one figure and dropped the other without saying so. Which reps count is not a
        // list handed in at the call — it is the subtree under the manager in the role hierarchy.
        Forecast one = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000001", "1000000.00", "2026-09-30")), 1L, yenRates()));
        Forecast two = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-002"), List.of(deal("006000000000002", "3000000.00", "2026-09-30")), 1L, yenRates()));

        Forecast team = assertInstanceOf(Forecast.class, RollupThrough.of().apply(team(),
                Map.of(user("u-001"), one, user("u-002"), two)));
        Map<String, Object> encoded = Forecast.encoder().encode(team);

        @SuppressWarnings("unchecked")
        Map<String, Object> byPeriod = (Map<String, Object>) encoded.get("byPeriod");
        assertEquals(0, ((BigDecimal) byPeriod.get("FY26-Q3")).compareTo(new BigDecimal("400000")),
                "100000 + 300000, not one of them");
        assertEquals("u-manager", encoded.get("owner"));
        assertEquals(2L, ((Number) encoded.get("dealCount")).longValue());
    }

    /** A deal sold in dollars is worth what the rate table says before it joins the total, and one in a
     *  currency the table does not price stops the forecast rather than quietly leaving. */
    @Test
    void aDealInAnotherCurrencyIsConvertedBeforeItIsAdded() {
        Forecast forecast = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"),
                List.of(deal("006000000000001", "1000000.00", "2026-09-30"),
                        deal("006000000000003", "1000.00", "2026-09-30", "USD")),
                1L, yenRates()));

        Map<String, Object> encoded = Forecast.encoder().encode(forecast);
        assertEquals("JPY", encoded.get("currency"));
        assertEquals(0, ((BigDecimal) encoded.get("total")).compareTo(new BigDecimal("115000")),
                "100000 yen-side plus 1000 USD at 150, weighted at ten per cent");

        assertInstanceOf(UnconvertibleCurrency.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000004", "1000.00", "2026-09-30", "EUR")),
                1L, yenRates()));
    }

    @Test
    void theCategoryRollupKeysOnTheCategoryName() {
        CategoryRollup rollup = CategoryRollupOf.of().apply(List.of(
                deal("006000000000001", "1000000.00", "2026-09-30"),
                deal("006000000000002", "2000000.00", "2026-09-30")));

        @SuppressWarnings("unchecked")
        Map<String, Object> byCategory =
                (Map<String, Object>) CategoryRollup.encoder().encode(rollup).get("byCategory");
        assertEquals(0, ((BigDecimal) byCategory.get("Pipeline")).compareTo(new BigDecimal("3000000")),
                "the category rollup is at face value, not weighted");
    }

    /** The compensation plan: a quota per owner per period, which is a different system's data. */
    static final class CompPlan extends QuotaFor {

        @Override
        public Quota apply(UserId owner, FiscalPeriod period) {
            String amount = owner.value().equals("u-001") ? "100000.00" : "500000.00";
            return ok(Quota.decoder().decode(new BigDecimal(amount)));
        }
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static Prospecting deal(String id, String amount, String closeDate) {
        return deal(id, amount, closeDate, "JPY");
    }

    private static Prospecting deal(String id, String amount, String closeDate, String currency) {
        return ok(Prospecting.decoder().decode(Map.of(
                "id", id,
                "accountId", "001000000000100",
                "name", "Acme Corp — New Business",
                "owner", "u-001",
                "amount", new BigDecimal(amount),
                "currency", currency,
                "closeDate", LocalDate.parse(closeDate),
                "openedOn", LocalDate.parse("2026-07-20"))));
    }

    /** Yen is the reporting currency and sits in the table at one, which {@code RateTable}'s invariant
     *  requires — so a yen deal takes the same path in as a dollar one. */
    private static RateTable yenRates() {
        return ok(RateTable.decoder().decode(Map.of(
                "base", "JPY",
                "rates", Map.of("JPY", BigDecimal.ONE, "USD", new BigDecimal("150.0")))));
    }

    /** A manager with the two reps reporting to them. */
    private static RoleNode team() {
        return ok(RoleNode.decoder().decode(Map.of(
                "role", "Manager", "holder", "u-manager", "reports", List.of(
                        Map.of("role", "Rep", "holder", "u-001", "reports", List.of()),
                        Map.of("role", "Rep", "holder", "u-002", "reports", List.of())))));
    }

    private static UserId user(String id) {
        return ok(UserId.decoder().decode(id));
    }

    private static Quota quota(String amount) {
        return ok(Quota.decoder().decode(new BigDecimal(amount)));
    }

    private static FiscalPeriod period(String label) {
        return ok(FiscalPeriod.decoder().decode(label));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
