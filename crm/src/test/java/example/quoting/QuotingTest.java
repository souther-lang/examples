package example.quoting;

import example.crm.UserId;
import example.pipeline.AssessPerception;
import example.pipeline.IdentifyDecisionMakers;
import example.pipeline.NeedsAnalysis;
import example.pipeline.PerceptionAnalysis;
import example.pipeline.ProposeValue;
import example.pipeline.Prospecting;
import example.pipeline.QualifyOpportunity;
import example.pipeline.Qualification;
import example.pipeline.QuoteNumber;
import example.pipeline.RecordNeeds;
import example.pipeline.ValueProposition;

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

/**
 * Building a quote, and the two CPQ caps.
 *
 * <p>The input is a {@code PerceptionAnalysis}, so this test walks example.pipeline's transitions to get one:
 * a fixture cannot be a state that only a sequence of transitions produces, which is exactly the guarantee
 * {@code buildQuote} is taking advantage of. A quote for a deal nobody took through discovery is not a call
 * that compiles, and the cost of that is that the test has to earn its input.
 *
 * <p>The load-bearing check is the blended cap. Every line in {@code aQuoteThatDodgesThePerLineCap} is inside
 * its own limit, and the quote is still refused, because the discount across the whole document is what a
 * customer sees and what a discount policy is actually about.
 */
class QuotingTest {

    @Test
    void aQuoteIsBuiltFromTheLinesAndItsTotalsAreDerived() {
        Quote quote = assertInstanceOf(Quote.class, BuildQuote.of().apply(
                quoteNumber(), discovered(),
                List.of(line("SW-1001", 10, "1000.00", "0.10"), line("SV-2002", 2, "500.00", "0.00")),
                LocalDate.parse("2026-08-10"), 30L));

        Map<String, Object> encoded = Quote.encoder().encode(quote);
        // A newtype over a List crosses as a bare array, not as an object with a "value" key.
        @SuppressWarnings("unchecked")
        List<Object> lines = (List<Object>) encoded.get("lines");
        assertEquals(2, lines.size());
        assertEquals(0, ((BigDecimal) encoded.get("subtotal")).compareTo(new BigDecimal("11000")));
        assertEquals(0, ((BigDecimal) encoded.get("total")).compareTo(new BigDecimal("10000")));
        assertEquals("2026-09-09", encoded.get("expiresOn").toString(), "thirty days from the issue date");
        assertEquals("NotSubmitted", ((Map<?, ?>) encoded.get("approval")).get("type"));
    }

    @Test
    void aQuoteThatDodgesThePerLineCapIsStillRefused() {
        // 45% off one line and nothing off the others. Every line is under the 50% line cap, and the blend is
        // past 40%, which is the rule that actually holds.
        assertInstanceOf(BlendedDiscountOverCap.class, BuildQuote.of().apply(
                quoteNumber(), discovered(),
                List.of(line("SW-1001", 10, "1000.00", "0.45"), line("SV-2002", 1, "100.00", "0.00")),
                LocalDate.parse("2026-08-10"), 30L));
    }

    @Test
    void twoLinesForOneProductIsNotAQuote() {
        assertInstanceOf(DuplicateProduct.class, BuildQuote.of().apply(
                quoteNumber(), discovered(),
                List.of(line("SW-1001", 10, "1000.00", "0.10"), line("SW-1001", 5, "1000.00", "0.10")),
                LocalDate.parse("2026-08-10"), 30L));
    }

    @Test
    void aQuoteWithNoLinesAndAQuoteGoodForeverAreBothRefused() {
        assertInstanceOf(NoLines.class, BuildQuote.of().apply(
                quoteNumber(), discovered(), List.of(), LocalDate.parse("2026-08-10"), 30L));

        assertInstanceOf(ValidityOutOfRange.class, BuildQuote.of().apply(
                quoteNumber(), discovered(), List.of(line("SW-1001", 10, "1000.00", "0.10")),
                LocalDate.parse("2026-08-10"), 91L));
    }

    @Test
    void aLineWhoseNetDisagreesWithItsOwnArithmeticIsRefusedAtTheBoundary() {
        // The invariant is a rule about three fields at once, so it is the boundary's job as much as the
        // model's: a stored row that has drifted cannot be decoded back into a QuoteLine.
        Result<QuoteLine> bad = QuoteLine.decoder().decode(Map.of(
                "product", "SW-1001",
                "name", "Platform licence",
                "quantity", 10L,
                "listPrice", new BigDecimal("1000.00"),
                "discount", new BigDecimal("0.10"),
                "net", new BigDecimal("9500.00")), Path.ROOT);
        assertInstanceOf(Err.class, bad);

        assertInstanceOf(Ok.class, QuoteLine.decoder().decode(Map.of(
                "product", "SW-1001",
                "name", "Platform licence",
                "quantity", 10L,
                "listPrice", new BigDecimal("1000.00"),
                "discount", new BigDecimal("0.10"),
                "net", new BigDecimal("9000.00")), Path.ROOT));
    }

    @Test
    void submittingResolvesTheRoleToAPersonThroughTheInjectedLookup() {
        // approverFor's success value is crm's UserId, which quoting cannot construct — so the implementation
        // builds it through crm's decoder. That is the ordinary shape when an injected behavior answers with
        // another context's type.
        SubmitForApproval submit = SubmitForApproval.bind(new OrgChart());

        Quote needsVp = assertInstanceOf(Quote.class, BuildQuote.of().apply(
                quoteNumber(), discovered(), List.of(line("SW-1001", 10, "1000.00", "0.30")),
                LocalDate.parse("2026-08-10"), 30L));

        Quote submitted = assertInstanceOf(Quote.class, submit.apply(needsVp, LocalDate.parse("2026-08-12")));
        Map<?, ?> approval = (Map<?, ?>) Quote.encoder().encode(submitted).get("approval");
        assertEquals("PendingApproval", approval.get("type"));
        assertEquals("u-vp", approval.get("approver"));

        // Submitting it again is a case, not a second submission.
        assertInstanceOf(AlreadySubmitted.class, submit.apply(submitted, LocalDate.parse("2026-08-13")));

        // A quote inside the rep's own authority has nobody to send it to.
        Quote small = assertInstanceOf(Quote.class, BuildQuote.of().apply(
                quoteNumber(), discovered(), List.of(line("SW-1001", 10, "1000.00", "0.05")),
                LocalDate.parse("2026-08-10"), 30L));
        Quote auto = assertInstanceOf(Quote.class, submit.apply(small, LocalDate.parse("2026-08-12")));
        assertEquals("AutoApproved",
                ((Map<?, ?>) Quote.encoder().encode(auto).get("approval")).get("type"));
    }

    @Test
    void aRejectionWithNoReasonIsRefusedAndAcceptingNeedsAnApproval() {
        SubmitForApproval submit = SubmitForApproval.bind(new OrgChart());
        Quote pending = assertInstanceOf(Quote.class, submit.apply(
                assertInstanceOf(Quote.class, BuildQuote.of().apply(
                        quoteNumber(), discovered(), List.of(line("SW-1001", 10, "1000.00", "0.30")),
                        LocalDate.parse("2026-08-10"), 30L)),
                LocalDate.parse("2026-08-12")));

        assertInstanceOf(RejectionNoteMissing.class, DecideApproval.of().apply(
                pending, user("u-vp"), LocalDate.parse("2026-08-13"), reject(), "   "));

        Quote rejected = assertInstanceOf(Quote.class, DecideApproval.of().apply(
                pending, user("u-vp"), LocalDate.parse("2026-08-13"), reject(), "margin too thin"));
        assertInstanceOf(ApprovalMissing.class,
                AcceptQuote.of().apply(rejected, LocalDate.parse("2026-08-20")));

        Quote approved = assertInstanceOf(Quote.class, DecideApproval.of().apply(
                pending, user("u-vp"), LocalDate.parse("2026-08-13"), approve(), ""));
        AcceptedQuote accepted = assertInstanceOf(AcceptedQuote.class,
                AcceptQuote.of().apply(approved, LocalDate.parse("2026-08-20")));
        assertEquals(0, ((BigDecimal) AcceptedQuote.encoder().encode(accepted).get("total"))
                .compareTo(new BigDecimal("7000")));
    }

    /** The org's approval matrix, which is configuration rather than domain. */
    static final class OrgChart extends ApproverFor {

        @Override
        public UserId apply(ApproverRole role) {
            String id = switch (role) {
                case SalesManager _ -> "u-manager";
                case RegionalVp _ -> "u-vp";
                case Cfo _ -> "u-cfo";
            };
            return ok(UserId.decoder().decode(id, Path.ROOT));
        }
    }

    // --- fixtures: the state is earned by walking the pipeline --------------------------------------

    private static PerceptionAnalysis discovered() {
        Prospecting prospecting = ok(Prospecting.decoder().decode(Map.of(
                "id", "006000000000001",
                "accountId", "001000000000100",
                "name", "Acme Corp — New Business",
                "owner", "u-001",
                "amount", new BigDecimal("11000.00"),
                "currency", "JPY",
                "closeDate", LocalDate.parse("2026-09-30"),
                "openedOn", LocalDate.parse("2026-07-20")), Path.ROOT));

        Qualification qualified = assertInstanceOf(Qualification.class, QualifyOpportunity.of()
                .apply(prospecting, LocalDate.parse("2026-07-25"), contact("003000000000100")));
        NeedsAnalysis needs = assertInstanceOf(NeedsAnalysis.class,
                RecordNeeds.of().apply(qualified, List.of("invoicing")));
        ValueProposition valued = assertInstanceOf(ValueProposition.class, ProposeValue.of().apply(
                needs, "Automating invoicing removes three days", List.of("total cost")));
        var identified = assertInstanceOf(example.pipeline.IdDecisionMakers.class,
                IdentifyDecisionMakers.of().apply(valued, List.of(contact("003000000000200"))));
        return assertInstanceOf(PerceptionAnalysis.class, AssessPerception.of()
                .apply(identified, contact("003000000000200"), List.of("migration risk")));
    }

    private static LineRequest line(String product, int qty, String list, String discount) {
        return ok(LineRequest.decoder().decode(Map.of(
                "product", product,
                "name", "Line " + product,
                "quantity", (long) qty,
                "listPrice", new BigDecimal(list),
                "discount", new BigDecimal(discount)), Path.ROOT));
    }

    private static QuoteNumber quoteNumber() {
        return ok(QuoteNumber.decoder().decode("0Q0000000000001", Path.ROOT));
    }

    private static example.crm.ContactId contact(String id) {
        return ok(example.crm.ContactId.decoder().decode(id, Path.ROOT));
    }

    private static UserId user(String id) {
        return ok(UserId.decoder().decode(id, Path.ROOT));
    }

    private static Verdict approve() {
        return ok(Verdict.decoder().decode("Approve", Path.ROOT));
    }

    private static Verdict reject() {
        return ok(Verdict.decoder().decode("Reject", Path.ROOT));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
