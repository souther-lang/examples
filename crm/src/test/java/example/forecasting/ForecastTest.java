package example.forecasting;

import example.crm.UserId;
import example.pipeline.Prospecting;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
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
                1L));

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
                user("u-001"), List.of(deal("006000000000001", "1000000.00", "2026-09-30")), 4L));

        @SuppressWarnings("unchecked")
        Map<String, Object> byPeriod = (Map<String, Object>) Forecast.encoder().encode(forecast).get("byPeriod");
        assertEquals(List.of("FY26-Q2"), List.copyOf(byPeriod.keySet()));
    }

    @Test
    void aThirteenthMonthIsRefused() {
        assertInstanceOf(InvalidFiscalYearStart.class,
                WeightedForecast.of().apply(user("u-001"), List.of(), 13L));
    }

    @Test
    void aQuarterWithNoDealsHasNoForecastRatherThanZero() {
        Forecast forecast = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000001", "1000000.00", "2026-09-30")), 1L));

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
                user("u-001"), List.of(deal("006000000000001", "1500000.00", "2026-09-30")), 1L));

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
    void aManagersForecastIsTheRepsSummedRatherThanOneOfThem() {
        // This is the assertion Map.union would have failed: both reps have a Q3, and a left-biased union
        // would have kept one figure and dropped the other without saying so.
        Forecast one = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-001"), List.of(deal("006000000000001", "1000000.00", "2026-09-30")), 1L));
        Forecast two = assertInstanceOf(Forecast.class, WeightedForecast.of().apply(
                user("u-002"), List.of(deal("006000000000002", "3000000.00", "2026-09-30")), 1L));

        Forecast team = RollupTeam.of().apply(user("u-manager"), List.of(one, two));
        Map<String, Object> encoded = Forecast.encoder().encode(team);

        @SuppressWarnings("unchecked")
        Map<String, Object> byPeriod = (Map<String, Object>) encoded.get("byPeriod");
        assertEquals(0, ((BigDecimal) byPeriod.get("FY26-Q3")).compareTo(new BigDecimal("400000")),
                "100000 + 300000, not one of them");
        assertEquals("u-manager", encoded.get("owner"));
        assertEquals(2L, ((Number) encoded.get("dealCount")).longValue());
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
            return ok(Quota.decoder().decode(new BigDecimal(amount), Path.ROOT));
        }
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static Prospecting deal(String id, String amount, String closeDate) {
        return ok(Prospecting.decoder().decode(Map.of(
                "id", id,
                "accountId", "001000000000100",
                "name", "Acme Corp — New Business",
                "owner", "u-001",
                "amount", new BigDecimal(amount),
                "currency", "JPY",
                "closeDate", LocalDate.parse(closeDate),
                "openedOn", LocalDate.parse("2026-07-20")), Path.ROOT));
    }

    private static UserId user(String id) {
        return ok(UserId.decoder().decode(id, Path.ROOT));
    }

    private static Quota quota(String amount) {
        return ok(Quota.decoder().decode(new BigDecimal(amount), Path.ROOT));
    }

    private static FiscalPeriod period(String label) {
        return ok(FiscalPeriod.decoder().decode(label, Path.ROOT));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
