package example.employee;

import example.socialinsurance.AcquireCoverage;
import example.socialinsurance.ContributionMonth;
import example.socialinsurance.ContributionRate;
import example.socialinsurance.ContributionRates;
import example.socialinsurance.ContributionRatesOf;
import example.socialinsurance.CoverageLost;
import example.socialinsurance.CoverageNotRequired;
import example.socialinsurance.Covered;
import example.socialinsurance.FinalContributionMonth;
import example.socialinsurance.HealthAndPension;
import example.socialinsurance.Insured;
import example.socialinsurance.JudgeCoverage;
import example.socialinsurance.JudgeCoverageResult;
import example.socialinsurance.LongTermCareInsured;
import example.socialinsurance.LoseCoverage;
import example.socialinsurance.Premium;
import example.socialinsurance.PremiumFor;
import example.socialinsurance.RegularRoute;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One employee walked from the offer letter to the last pay slip, across four modules and over the
 * boundary. The person arrives as a nested {@code Map} — four records deep, with an enumeration as a bare
 * string and a sum as an object carrying its tag — and everything after that is generated types calling
 * each other.
 *
 * <p>The rules themselves are already pinned at compile time by the {@code example} rows in the
 * {@code .sou} files. What this adds is the two things those cannot reach: that the derived codec reads
 * the whole shape back, and that an injected behavior is implementable from plain Java with no framework.
 *
 * <p>The load-bearing assertion is the last pair. Resigning on 30 March and resigning on the 31st differ
 * by one day and by one month of premium for both the employee and the employer, because coverage is lost
 * the day <em>after</em> separation and premiums run to the month before that. Everything else in this
 * file is the road to those two lines.
 */
class EmploymentLifecycleTest {

    @Test
    void anOfferBecomesAnEmployeeAndTheEmployeeBecomesInsured() {
        Employed employed = hireFrom(prospectRaw());

        assertEquals("000001", employed.identity().id().value());
        assertEquals(LocalDate.parse("2026-04-01"), employed.hiredOn(),
                "the hire date is the date the offer named, carried across by the spread");

        Covered covered = assertInstanceOf(Covered.class, judgeCoverage(employed));
        assertInstanceOf(RegularRoute.class, covered.route());
        assertInstanceOf(HealthAndPension.class, covered.scope());

        Insured insured = AcquireCoverage.of().apply(covered, employed.remuneration(), employed.hiredOn());
        assertEquals(300_000L, insured.decision().standard().value(),
                "260,000 of base plus 40,000 of allowances lands in the 290,000-to-310,000 band");
        assertEquals(22L, insured.decision().health().value());
        assertEquals(19L, insured.decision().pension().value(),
                "pension grade 19 is health grade 22 less three, which is what the statute says it is");
    }

    @Test
    void somebodyWhoHasAttainedSeventyFiveIsInsuredElsewhere() {
        Map<String, Object> raw = prospectRaw();
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) raw.get("identity");
        identity.put("birthday", LocalDate.parse("1951-01-01"));

        Employed employed = hireFrom(raw);
        CoverageNotRequired refused =
                assertInstanceOf(CoverageNotRequired.class, judgeCoverage(employed));

        @SuppressWarnings("unchecked")
        var reasons = (java.util.List<Object>) CoverageNotRequired.encoder().encode(refused).get("reasons");
        assertEquals(1, reasons.size(), reasons.toString());
        assertEquals("AttainedSeventyFive", ((Map<?, ?>) reasons.get(0)).get("type"));
    }

    /**
     * The injected behavior, bound from plain Java. The rates come back through the factory the base class
     * inherits for the type its declaration constructs; a real implementation would read them from the
     * association's published table and would throw rather than return a case if that table were
     * unreachable.
     */
    @Test
    void theTwoHalvesOfAPremiumAreNotEqual() {
        PremiumFor premiumFor = PremiumFor.bind(new TokyoRates());
        Premium premium = premiumFor.apply(
                yen(110_000), HealthAndPension_(), LongTermCareInsured_(),
                prefecture("13"), LocalDate.parse("2026-07-01"));

        assertEquals(5_450L, premium.health().employee().value());
        assertEquals(5_451L, premium.health().employer().value(),
                "the employee's half is rounded down at 50 sen and the employer pays the rest");
        assertEquals(874L, premium.longTermCare().employee().value());
        assertEquals(875L, premium.longTermCare().employer().value());
        assertEquals(10_065L, premium.pension().employee().value(),
                "an exact half divides evenly, which is what makes the other two worth asserting");
    }

    @Test
    void oneDayOfSeparationIsOneMonthOfPremium() {
        Employed employed = hireFrom(prospectRaw());
        Covered covered = assertInstanceOf(Covered.class, judgeCoverage(employed));
        Insured insured = AcquireCoverage.of().apply(covered, employed.remuneration(), employed.hiredOn());

        assertEquals(LocalDate.parse("2027-02-01"), lastPremiumMonthAfterLeavingOn(insured, "2027-03-30"),
                "leaving on the 30th loses coverage on the 31st, so March is never collected");
        assertEquals(LocalDate.parse("2027-03-01"), lastPremiumMonthAfterLeavingOn(insured, "2027-03-31"),
                "leaving on the 31st loses coverage on 1 April, so March is");
    }

    @Test
    void aRecordWhoseInvariantDoesNotHoldNeverBecomesAValue() {
        Map<String, Object> raw = prospectRaw();
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) raw.get("identity");
        identity.put("myNumber", "12345678901");   // eleven digits

        Result<Prospective> decoded = Prospective.decoder().decode(raw);
        Err<Prospective> failure = assertInstanceOf(Err.class, decoded);
        assertTrue(failure.issues().asList().toString().contains("myNumber"),
                "the path says which field was refused: " + failure.issues().asList());
    }

    // === What the boundary wires ===

    /** Tokyo's association rates for the 2026 fiscal year. Two arguments in, one record out. */
    static final class TokyoRates extends ContributionRatesOf {

        @Override
        public ContributionRates apply(PrefectureCode prefecture, LocalDate on) {
            return ContributionRates(rate("0.0991"), rate("0.0159"), rate("0.183"));
        }

        private ContributionRate rate(String value) {
            return switch (ContributionRate.decoder().decode(new BigDecimal(value))) {
                case Ok<ContributionRate> ok -> ok.value();
                case Err<ContributionRate> e -> throw new IllegalStateException(e.issues().asList().toString());
            };
        }
    }

    // === Helpers ===

    private static LocalDate lastPremiumMonthAfterLeavingOn(Insured insured, String separatedOn) {
        CoverageLost lost = assertInstanceOf(CoverageLost.class,
                LoseCoverage.of().apply(insured, LocalDate.parse(separatedOn)));
        ContributionMonth month = FinalContributionMonth.of().apply(lost);
        return month.value();
    }

    private static JudgeCoverageResult judgeCoverage(Employed employed) {
        return JudgeCoverage.of().apply(
                employed.terms(),
                employed.remuneration(),
                office(),
                weeklyHours("40.0"),
                employed.identity().birthday(),
                employed.hiredOn());
    }

    private static Employed hireFrom(Map<String, Object> raw) {
        return Hire.of().apply(decode(Prospective.decoder(), raw));
    }

    private static Map<String, Object> prospectRaw() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("id", "000001");
        identity.put("birthday", LocalDate.parse("1988-05-20"));
        identity.put("sex", "Female");
        identity.put("myNumber", "123456789018");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("identity", identity);
        raw.put("personalName", Map.of("name", "山田花子", "kana", "ヤマダハナコ"));
        raw.put("residence", Map.of("address", Map.of(
                "postalCode", "100-0001", "prefecture", "13", "line", "千代田区千代田1-1")));
        raw.put("terms", Map.of(
                "officeId", "13-000001",
                "employmentType", "FullTime",
                "weeklyHours", new BigDecimal("40.0"),
                "contract", Map.of("type", "Indefinite"),
                "student", "NotDaytimeStudent"));
        raw.put("remuneration", Map.of(
                "baseSalary", 260_000,
                "scheduledAllowances", 20_000,
                "commutingAllowance", 15_000,
                "excludedAllowances", 5_000,
                "variableAllowances", 0));
        raw.put("hireOn", LocalDate.parse("2026-04-01"));
        return raw;
    }

    private static Office office() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "13-000001");
        raw.put("prefecture", "13");
        raw.put("insuredCount", 120);
        return decode(Office.decoder(), raw);
    }

    private static WeeklyScheduledHours weeklyHours(String value) {
        return decode(WeeklyScheduledHours.decoder(), new BigDecimal(value));
    }

    private static Yen yen(long value) {
        return decode(Yen.decoder(), value);
    }

    private static PrefectureCode prefecture(String value) {
        return decode(PrefectureCode.decoder(), value);
    }

    private static example.socialinsurance.InsuranceScope HealthAndPension_() {
        return decode(example.socialinsurance.InsuranceScope.decoder(), "HealthAndPension");
    }

    private static example.socialinsurance.LongTermCareStatus LongTermCareInsured_() {
        return decode(example.socialinsurance.LongTermCareStatus.decoder(), "LongTermCareInsured");
    }

    private static <I, T> T decode(net.unit8.raoh.decode.Decoder<I, T> decoder, I raw) {
        return switch (decoder.decode(raw)) {
            case Ok<T> ok -> ok.value();
            case Err<T> e -> throw new IllegalStateException(e.issues().asList().toString());
        };
    }
}
