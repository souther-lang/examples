package example.pipeline;

import example.crm.Amount;
import example.crm.ContactId;

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
 * The ten stages, driven end to end, and then the reports read off the result.
 *
 * <p>The load-bearing check is the one that cannot be written: there is no test here for advancing a deal
 * out of order, because each transition takes the exact stage it advances from, so
 * {@code sendProposal(prospecting, ...)} is not an expression that compiles. What the walk below proves is
 * that the legal path is a path — every state the pipeline produces is accepted by the next transition
 * with nothing re-supplied and nothing re-checked.
 *
 * <p>This class sits in the generated package so it can reach the report behaviors the module
 * deliberately does not expose: not being on the {@code exposing} list makes a generated class
 * package-private, and a smoke test in the same package is exactly who is still allowed to read it.
 *
 * <p>What is no longer here is the pin for {@code closeAndSummarize}. A composition carries an
 * {@code example} now, so the routing — a win summarised, and a below-floor departure carried to the
 * composition's output — is stated in {@code pipeline.sou} beside the behaviors it is about.
 */
class PipelineTest {

    @Test
    void aDealWalksAllTenStagesAndTheStatesCarryWhatEachStageAgreed() {
        Prospecting prospecting = prospecting("006000000000001", "4800000.00");

        Qualification qualified = assertInstanceOf(Qualification.class,
                QualifyOpportunity.of().apply(prospecting, date("2026-07-25"), contact("003000000000100")));

        NeedsAnalysis discovered = assertInstanceOf(NeedsAnalysis.class,
                RecordNeeds.of().apply(qualified, List.of("invoicing", "  ", "no audit trail")));
        assertEquals(2, discovered.needs().value().size(), "the blank entry is not a need");

        ValueProposition valued = assertInstanceOf(ValueProposition.class, ProposeValue.of().apply(
                discovered, "Automating invoicing removes three days of manual work",
                List.of("total cost", "security review")));

        IdDecisionMakers identified = assertInstanceOf(IdDecisionMakers.class,
                IdentifyDecisionMakers.of().apply(valued,
                        List.of(contact("003000000000100"), contact("003000000000200"))));

        PerceptionAnalysis assessed = assertInstanceOf(PerceptionAnalysis.class, AssessPerception.of().apply(
                identified, contact("003000000000200"), List.of("migration risk")));
        assertEquals("003000000000200", assessed.champion().value(),
                "the champion is one of the people who can say yes");

        ProposalPriceQuote proposed = assertInstanceOf(ProposalPriceQuote.class, SendProposal.of().apply(
                assessed, quoteNumber("0Q0000000000001"), date("2026-08-10"), amount("4600000.00")));

        NegotiationReview negotiating = assertInstanceOf(NegotiationReview.class,
                OpenNegotiation.of().apply(proposed, date("2026-09-01"), amount("4200000.00")));

        ClosedWon won = assertInstanceOf(ClosedWon.class,
                CloseWon.of().apply(negotiating, date("2026-09-20"), amount("4400000.00")));

        // Money is compared by value, not by scale: an encoded BigDecimal keeps the scale it was written
        // with, and 4400000.00 is the same number as 4400000.
        assertEquals(0, ((BigDecimal) ClosedWon.encoder().encode(won).get("wonAmount"))
                .compareTo(new BigDecimal("4400000")), "the deal closed at what was negotiated");
        // The commitments made on the way through are still in the state at the end.
        assertEquals("0Q0000000000001", won.quoteNumber().value());
        assertEquals(2, won.decisionMakers().value().size());
    }

    @Test
    void aChampionOutsideTheDecisionMakersIsRefused() {
        IdDecisionMakers identified = identified();

        assertInstanceOf(ChampionNotADecisionMaker.class, AssessPerception.of().apply(
                identified, contact("003000000000999"), List.of("no sponsor")));
    }

    @Test
    void anOfferBelowTheFloorIsRefusedAndTheFloorIsInTheAnswer() {
        NegotiationReview negotiating = assertInstanceOf(NegotiationReview.class,
                OpenNegotiation.of().apply(proposal(), date("2026-09-01"), amount("4200000.00")));

        BelowFloor refused = assertInstanceOf(BelowFloor.class,
                CloseWon.of().apply(negotiating, date("2026-09-20"), amount("4000000.00")));
        assertEquals(0, ((BigDecimal) BelowFloor.encoder().encode(refused).get("floor"))
                .compareTo(new BigDecimal("4200000")));

        // Out of a proposal there is no floor to be below, and no argument to pass one in either.
        assertInstanceOf(ClosedWon.class, CloseWon.of().apply(proposal(), date("2026-09-20"), amount("1.00")));
    }

    @Test
    void aFloorAboveTheAskIsNotANegotiation() {
        assertInstanceOf(FloorAboveAsk.class,
                OpenNegotiation.of().apply(proposal(), date("2026-09-01"), amount("9000000.00")));
    }

    @Test
    void withdrawingAProposalDropsTheFieldsItInvalidated() {
        ProposalPriceQuote proposed = proposal();
        PerceptionAnalysis back = WithdrawProposal.of().apply(proposed);

        Map<String, Object> encoded = PerceptionAnalysis.encoder().encode(back);
        assertEquals(null, encoded.get("quoteNumber"), "a withdrawn proposal has no quote number");
        assertEquals(null, encoded.get("proposedAmount"));
        // What the deal had agreed before the quote is still there.
        assertEquals("003000000000200", ((String) encoded.get("champion")));
    }

    @Test
    void aLostDealRecordsHowFarItGot() {
        ClosedLost lost = CloseLost.of().apply(qualification(), date("2026-08-20"),
                lossReason("CompetitorLoss", "Rival KK"));

        Map<String, Object> encoded = ClosedLost.encoder().encode(lost);
        assertEquals("Qualification", encoded.get("stageAtLoss"));
        // A nested sum crosses the boundary as its leaf tag: DisplacedLoss never appears.
        assertEquals("CompetitorLoss", ((Map<?, ?>) encoded.get("reason")).get("type"));
    }

    @Test
    void thePipelineIsCountedAndTotalledByStageName() {
        List<Opportunity> board = List.of(
                prospecting("006000000000001", "1000000.00"),
                prospecting("006000000000002", "2000000.00"),
                qualification(),
                CloseLost.of().apply(qualification(), date("2026-08-20"), lossReason("NoBudget", null)));

        StageBreakdown breakdown = PipelineByStage.of().apply(board);
        Map<String, Object> encoded = StageBreakdown.encoder().encode(breakdown);

        @SuppressWarnings("unchecked")
        Map<String, Object> count = (Map<String, Object>) encoded.get("count");
        assertEquals(2L, ((Number) count.get("Prospecting")).longValue());
        assertEquals(1L, ((Number) count.get("Qualification")).longValue());
        assertEquals(1L, ((Number) count.get("Closed Lost")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) encoded.get("total");
        assertEquals(0, ((BigDecimal) total.get("Prospecting")).compareTo(new BigDecimal("3000000")),
                "the absent branch inserts the first deal's figure and the rest add to it");

        assertEquals(3, ((Number) encoded.get("openCount")).intValue(), "the lost deal is not open");
        // The stage keys come back in order, which is what List.sort over a String-backed newtype buys.
        assertEquals(List.of("Closed Lost", "Prospecting", "Qualification"), encoded.get("stages"));
    }

    @Test
    void aPeriodWithNoClosedDealsHasNoWinRateRatherThanZero() {
        ClosedWon won = assertInstanceOf(ClosedWon.class,
                CloseWon.of().apply(negotiation(), date("2026-09-20"), amount("4400000.00")));
        ClosedLost lost = CloseLost.of().apply(qualification(), date("2026-08-20"), lossReason("NoBudget", null));

        WinRate rate = assertInstanceOf(WinRate.class, WinRateOf.of().apply(List.of(won, lost)));
        assertEquals(50, rate.rate().value());

        assertInstanceOf(NoClosedDeals.class, WinRateOf.of().apply(List.of()));
    }

    @Test
    void theTopDealsAreRankedByWhatTheyAreWorth() {
        DealRanking ranking = TopDeals.of().apply(List.of(
                prospecting("006000000000001", "1000000.00"),
                prospecting("006000000000003", "3000000.00"),
                prospecting("006000000000002", "2000000.00")), 2L);

        assertEquals(List.of("006000000000003", "006000000000002"),
                DealRanking.encoder().encode(ranking).get("deals"));
    }

    // --- fixtures, built by decoding, because a generated constructor is not public -----------------

    private static Prospecting prospecting(String id, String amount) {
        return ok(Prospecting.decoder().decode(Map.of(
                "id", id,
                "accountId", "001000000000100",
                "name", "Acme Corp — New Business",
                "owner", "u-001",
                "amount", new BigDecimal(amount),
                "currency", "JPY",
                "closeDate", LocalDate.parse("2026-09-30"),
                "openedOn", LocalDate.parse("2026-07-20")), Path.ROOT));
    }

    private static Qualification qualification() {
        return assertInstanceOf(Qualification.class, QualifyOpportunity.of().apply(
                prospecting("006000000000001", "4800000.00"), date("2026-07-25"), contact("003000000000100")));
    }

    private static IdDecisionMakers identified() {
        NeedsAnalysis discovered = assertInstanceOf(NeedsAnalysis.class,
                RecordNeeds.of().apply(qualification(), List.of("invoicing")));
        ValueProposition valued = assertInstanceOf(ValueProposition.class, ProposeValue.of().apply(
                discovered, "Automating invoicing removes three days of manual work", List.of("total cost")));
        return assertInstanceOf(IdDecisionMakers.class, IdentifyDecisionMakers.of().apply(valued,
                List.of(contact("003000000000100"), contact("003000000000200"))));
    }

    private static ProposalPriceQuote proposal() {
        PerceptionAnalysis assessed = assertInstanceOf(PerceptionAnalysis.class, AssessPerception.of()
                .apply(identified(), contact("003000000000200"), List.of("migration risk")));
        return assertInstanceOf(ProposalPriceQuote.class, SendProposal.of().apply(
                assessed, quoteNumber("0Q0000000000001"), date("2026-08-10"), amount("4600000.00")));
    }

    private static NegotiationReview negotiation() {
        return assertInstanceOf(NegotiationReview.class,
                OpenNegotiation.of().apply(proposal(), date("2026-09-01"), amount("4200000.00")));
    }

    private static LossReason lossReason(String tag, String company) {
        Map<String, Object> raw = company == null
                ? Map.of("type", tag)
                : Map.of("type", tag, tag.equals("CompetitorLoss") ? "competitor" : "incumbent", company);
        return ok(LossReason.decoder().decode(raw, Path.ROOT));
    }

    private static Amount amount(String value) {
        return ok(Amount.decoder().decode(new BigDecimal(value), Path.ROOT));
    }

    private static ContactId contact(String id) {
        return ok(ContactId.decoder().decode(id, Path.ROOT));
    }

    private static QuoteNumber quoteNumber(String number) {
        return ok(QuoteNumber.decoder().decode(number, Path.ROOT));
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
