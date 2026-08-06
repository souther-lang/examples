package example.ledger;

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
 * Double entry as a condition on construction. ledger.sou's {@code example}s fix what each posting
 * behavior produces; what is checked here is that the rule survives the boundary — an entry arriving
 * as JSON is rejected by the same invariant, so nothing outside the domain can hand in a lopsided one
 * either.
 */
class LedgerTest {

    private static Map<String, Object> posting(String account, String side, String amount) {
        return Map.of("account", account, "side", side, "amount", new BigDecimal(amount));
    }

    /** {@code decoder()} reads Java values, so a {@code Date} field wants a {@code LocalDate} here;
     *  it is {@code jsonDecoder()} that reads the ISO text a request body carries. */
    private static Map<String, Object> entry(List<Map<String, Object>> postings) {
        return Map.of("date", LocalDate.parse("2026-07-25"), "source", "INV-2026-000042",
                "postings", postings);
    }

    @Test
    void anEntryWhoseSidesAgreeDecodes() {
        Result<JournalEntry> r = JournalEntry.decoder().decode(entry(List.of(
                posting("1310", "Debit", "346.0"),
                posting("4100", "Credit", "315.0"),
                posting("2170", "Credit", "31.0"))));

        JournalEntry decoded = ((Ok<JournalEntry>) assertInstanceOf(Ok.class, r)).value();
        assertEquals(3, decoded.postings().size());
    }

    /** A yen out on one side, and the value does not exist. This is the whole of the pattern: not a
     *  report that goes red at month end, but an entry that cannot be built to be posted. */
    @Test
    void anEntryAYenOutOfBalanceIsRejected() {
        Result<JournalEntry> r = JournalEntry.decoder().decode(entry(List.of(
                posting("1310", "Debit", "346.0"),
                posting("4100", "Credit", "315.0"),
                posting("2170", "Credit", "30.0"))));

        Err<JournalEntry> err = (Err<JournalEntry>) assertInstanceOf(Err.class, r);
        assertTrue(err.issues().asList().toString().contains("balanced"),
                "the failing invariant is named: " + err.issues().asList());
    }

    /** One posting has nothing to balance against, and the other invariant says so. */
    @Test
    void aSinglePostingIsNotAnEntry() {
        Result<JournalEntry> r = JournalEntry.decoder().decode(
                entry(List.of(posting("1310", "Debit", "0.0"))));

        Err<JournalEntry> err = (Err<JournalEntry>) assertInstanceOf(Err.class, r);
        assertTrue(err.issues().asList().toString().contains("twoSided"),
                "the failing invariant is named: " + err.issues().asList());
    }

    /** The journal read as a trial balance: the receivable is netted down by the credit note, the
     *  return keeps its own account, and the two totals agree — which the type states, so a fold that
     *  lost a figure could not have produced this value. */
    @Test
    void theJournalBalancesOff() {
        JournalEntry invoice = ok(JournalEntry.decoder().decode(entry(List.of(
                posting("1310", "Debit", "346.0"),
                posting("4100", "Credit", "315.0"),
                posting("2170", "Credit", "31.0")))));
        JournalEntry credit = ok(JournalEntry.decoder().decode(entry(List.of(
                posting("4300", "Debit", "100.0"),
                posting("2170", "Debit", "10.0"),
                posting("1310", "Credit", "110.0")))));

        TrialBalance trial = BalanceOff.of().apply(List.of(invoice, credit));

        Map<String, Object> encoded = TrialBalance.encoder().encode(trial);
        assertEquals(0, new BigDecimal("336.0").compareTo((BigDecimal) encoded.get("debitTotal")));
        assertEquals(encoded.get("debitTotal"), encoded.get("creditTotal"));
        assertEquals(List.of("DebitBalance", "CreditBalance", "CreditBalance", "DebitBalance"),
                ((List<?>) encoded.get("balances")).stream()
                        .map(b -> ((Map<?, ?>) b).get("type")).toList());
    }

    private <T> T ok(Result<T> r) {
        if (r instanceof Err<T> e) {
            throw new AssertionError("should decode: " + e.issues().asList());
        }
        return ((Ok<T>) r).value();
    }
}
