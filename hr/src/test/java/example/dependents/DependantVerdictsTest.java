package example.dependents;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same person put to both dependant tests, over the boundary. A spouse earning 1.2 million yen is a
 * dependant for health insurance and is not one for income tax, and the two answers come back from two
 * behaviors that were handed two different measures of the same money.
 *
 * <p>What the {@code example} rows in dependents.sou already fix is which arm each input lands on. What
 * this adds is the encoded shape of a refusal — a refusal is a list of reasons and the person filling in
 * the form has to see all of them — and the decode failure an unknown relationship produces, which is the
 * boundary refusing a value rather than the domain aborting on one.
 */
class DependantVerdictsTest {

    @Test
    void theHealthInsuranceTakesTheSpouseTheTaxRulesRefuse() {
        DependantPerson spouse = person("1988-05-20");
        RelativeTie tie = tie("Spouse", livingTogether());

        InsuranceDependant insured = assertInstanceOf(InsuranceDependant.class,
                QualifyAsInsuranceDependant.of().apply(
                        spouse, tie, revenue(1_200_000), revenue(6_000_000), LocalDate.parse("2026-07-01")));
        assertEquals(1_300_000L, insured.appliedCeiling().value(),
                "under sixty and not disabled, so the ceiling is the ordinary one");

        TaxDependencyBlocked refused = assertInstanceOf(TaxDependencyBlocked.class,
                ClassifyTaxDependant.of().apply(
                        spouse, tie, income(650_000), LocalDate.parse("2026-12-31")));

        List<Object> reasons = reasonsOf(TaxDependencyBlocked.encoder().encode(refused));
        assertEquals(2, reasons.size(), reasons.toString());
        assertEquals("SpouseIsDeductedSeparately", tagOf(reasons.get(0)),
                "a spouse is deducted under the spouse provisions, not as a dependant relative");
        assertEquals("IncomeOverTaxCeiling", tagOf(reasons.get(1)),
                "and the income is over the ceiling as well, which the form has to say too");
    }

    @Test
    void aMotherLivingApartOnARemittanceSharesTheLivelihoodButNotTheHousehold() {
        DependantPerson mother = person("1952-01-01");
        RelativeTie tie = tie("LinealAscendant", livingApart(1_200_000));

        TaxDependant deducted = assertInstanceOf(TaxDependant.class,
                ClassifyTaxDependant.of().apply(mother, tie, income(0), LocalDate.parse("2026-12-31")));
        assertEquals("ElderlyDependant",
                TaxDependant.encoder().encode(deducted).get("category"),
                "elderly, but not a coresident elderly parent — she lives in another city");

        // The same tie, judged by the other system: a remittance smaller than the revenue supports nobody.
        assertInstanceOf(InsuranceDependencyBlocked.class,
                QualifyAsInsuranceDependant.of().apply(
                        mother, tie, revenue(1_250_000), revenue(6_000_000), LocalDate.parse("2026-07-01")));
    }

    @Test
    void aRelationshipNobodyDeclaredIsRefusedAtTheBoundary() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("relationship", Map.of("type", "Cousin"));
        raw.put("cohabitation", livingTogether());

        Result<RelativeTie> decoded = RelativeTie.decoder().decode(raw, Path.ROOT);
        Err<RelativeTie> failure = assertInstanceOf(Err.class, decoded);
        assertTrue(failure.issues().asList().toString().contains("relationship"),
                "the path names the field the unknown tag was under: " + failure.issues().asList());
    }

    // === Helpers ===

    private static List<Object> reasonsOf(Map<String, Object> encoded) {
        @SuppressWarnings("unchecked")
        List<Object> reasons = (List<Object>) encoded.get("reasons");
        return reasons;
    }

    private static Object tagOf(Object reason) {
        return ((Map<?, ?>) reason).get("type");
    }

    private static Map<String, Object> livingTogether() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "LivingTogether");
        return raw;
    }

    private static Map<String, Object> livingApart(long annualRemittance) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "LivingApart");
        raw.put("annualRemittance", annualRemittance);
        return raw;
    }

    private static DependantPerson person(String birthday) {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("name", "山田花子");
        name.put("kana", "ヤマダハナコ");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", name);
        raw.put("birthday", LocalDate.parse(birthday));
        raw.put("disability", "NotDisabled");
        return decode(DependantPerson.decoder(), raw);
    }

    private static RelativeTie tie(String relationship, Map<String, Object> cohabitation) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("relationship", Map.of("type", relationship));
        raw.put("cohabitation", cohabitation);
        return decode(RelativeTie.decoder(), raw);
    }

    private static AnnualRevenue revenue(long value) {
        return decode(AnnualRevenue.decoder(), value);
    }

    private static TotalIncome income(long value) {
        return decode(TotalIncome.decoder(), value);
    }

    private static <I, T> T decode(net.unit8.raoh.decode.Decoder<I, T> decoder, I raw) {
        return switch (decoder.decode(raw, Path.ROOT)) {
            case Ok<T> ok -> ok.value();
            case Err<T> e -> throw new IllegalStateException(e.issues().asList().toString());
        };
    }
}
