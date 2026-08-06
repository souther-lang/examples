package example.payroll;

import example.employee.Yen;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pay slip built, encoded, and read back — and one that is not built, because its arithmetic does not
 * hold.
 *
 * <p>Two things are on show here that the {@code .sou} rows cannot reach. A slip's invariant says net
 * plus every deduction is gross, and it is checked on <em>every</em> construction path — so a slip
 * arriving from a database with a net that does not add up is refused by the decoder rather than
 * believed. And an agreed deduction encodes with the agreement it rests on beside it, which is what
 * makes "wages are paid in full unless something authorises the deduction" a property of the value
 * rather than a paragraph in a manual.
 */
class PaySlipBoundaryTest {

    @Test
    void grossLessEveryDeductionIsNetAndTheAgreementTravelsWithTheLineThatNeededIt() {
        PaySlip slip = assertInstanceOf(PaySlip.class,
                BuildPaySlip.of().apply(payMonth("2026-07-01"), earnings(300_000, 22_750), julyDeductions()));

        Map<String, Object> encoded = PaySlip.encoder().encode(slip);
        assertEquals(270_035L, ((Number) encoded.get("net")).longValue());

        @SuppressWarnings("unchecked")
        List<Object> lines = (List<Object>) encoded.get("deductions");
        assertEquals(5, lines.size());
        assertEquals("StatutoryDeduction", ((Map<?, ?>) lines.get(0)).get("type"));
        assertEquals("HealthInsurancePremium", ((Map<?, ?>) lines.get(0)).get("kind"),
                "an enumeration crosses as the case's name and nothing else");

        Map<?, ?> agreed = (Map<?, ?>) lines.get(4);
        assertEquals("AgreedDeduction", agreed.get("type"));
        assertEquals("R08-001", agreed.get("agreement"),
                "the union dues line carries the agreement that authorises it");
    }

    @Test
    void aMonthWithNothingToDeductFromIsAnAnswerRatherThanAnAbort() {
        assertInstanceOf(DeductionsExceedGross.class,
                BuildPaySlip.of().apply(payMonth("2026-07-01"), earnings(0, 0), julyDeductions()));
    }

    @Test
    void aStoredSlipWhoseArithmeticDoesNotHoldIsRefusedByTheDecoder() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("month", LocalDate.parse("2026-07-01"));
        raw.put("earnings", Map.of("fixed", 300_000, "uplift", 22_750));
        raw.put("deductions", rawJulyDeductions());
        raw.put("net", 270_036);   // one yen out

        Result<PaySlip> decoded = PaySlip.decoder().decode(raw);
        Err<PaySlip> failure = assertInstanceOf(Err.class, decoded);
        assertTrue(failure.issues().asList().toString().contains("invariant"),
                "the slip's own clause is what refuses it: " + failure.issues().asList());
    }

    /**
     * The withholding table, implemented from plain Java. Its two arguments are the amount and the number
     * of dependants — the column of the published monthly table — and the amount it answers with is built
     * through {@code Yen}'s decoder, because an implementation is not granted a constructor.
     *
     * <p>{@code taxableAmount} answers {@code Yen | DeductionsExceedGross}, and {@code Yen} belongs to
     * {@code example.employee}, so a class this module emitted cannot be given the union's interface.
     * It arrives as {@code YenCase} — the bridge case {@code example.payroll} emits — while the failure
     * this module declares arrives as itself. Both are permitted, so the {@code switch} needs no
     * {@code default}.
     */
    @Test
    void theWithholdingTableIsReadFromOutsideTheModel() {
        WithholdingTaxOf table = new FlatRateTable();
        Yen taxable = switch (TaxableAmount.of().apply(remuneration(), yen(22_750), yen(43_965))) {
            case YenCase amount -> amount.value();
            case DeductionsExceedGross refused ->
                    throw new AssertionError("these deductions do not exceed the gross: " + refused);
        };

        assertEquals(263_785L, taxable.value());
        assertEquals(13_189L, table.apply(taxable, 1L).value(),
                "five per cent of the taxable amount, which is not the real table and is not the point");
    }

    /**
     * A stand-in for the four-hundred-row monthly table, which is republished every year. Note the
     * {@code Long}: an {@code Int} parameter of an injected behavior arrives boxed, where a generated
     * record's accessor returns a primitive {@code long}.
     */
    static final class FlatRateTable extends WithholdingTaxOf {

        @Override
        public Yen apply(Yen taxable, Long dependants) {
            return decode(Yen.decoder(), taxable.value() * 5 / 100);
        }
    }

    // === Helpers ===

    private static example.employee.MonthlyRemuneration remuneration() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("baseSalary", 260_000);
        raw.put("scheduledAllowances", 20_000);
        raw.put("commutingAllowance", 15_000);
        raw.put("excludedAllowances", 5_000);
        raw.put("variableAllowances", 0);
        return decode(example.employee.MonthlyRemuneration.decoder(), raw);
    }

    private static List<Deduction> julyDeductions() {
        List<Deduction> lines = new ArrayList<>();
        for (Map<String, Object> raw : rawJulyDeductions()) {
            lines.add(decode(Deduction.decoder(), raw));
        }
        return lines;
    }

    private static List<Map<String, Object>> rawJulyDeductions() {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(statutory("HealthInsurancePremium", 14_865));
        lines.add(statutory("PensionPremium", 27_450));
        lines.add(statutory("EmploymentInsurancePremium", 1_650));
        lines.add(statutory("WithholdingIncomeTax", 6_750));

        Map<String, Object> agreed = new LinkedHashMap<>();
        agreed.put("type", "AgreedDeduction");
        agreed.put("name", "組合費");
        agreed.put("amount", 2_000);
        agreed.put("agreement", "R08-001");
        lines.add(agreed);
        return lines;
    }

    private static Map<String, Object> statutory(String kind, long amount) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "StatutoryDeduction");
        raw.put("kind", kind);
        raw.put("amount", amount);
        return raw;
    }

    private static Earnings earnings(long fixed, long uplift) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fixed", fixed);
        raw.put("uplift", uplift);
        return decode(Earnings.decoder(), raw);
    }

    private static PayMonth payMonth(String day) {
        return decode(PayMonth.decoder(), LocalDate.parse(day));
    }

    private static Yen yen(long value) {
        return decode(Yen.decoder(), value);
    }

    private static <I, T> T decode(net.unit8.raoh.decode.Decoder<I, T> decoder, I raw) {
        return switch (decoder.decode(raw)) {
            case Ok<T> ok -> ok.value();
            case Err<T> e -> throw new IllegalStateException(e.issues().asList().toString());
        };
    }
}
