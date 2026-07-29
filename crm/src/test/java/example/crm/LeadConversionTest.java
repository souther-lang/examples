package example.crm;

import example.pipeline.NextOpportunityId;
import example.pipeline.NoOpportunityRequested;
import example.pipeline.OpenFromLead;
import example.pipeline.OpportunityId;
import example.pipeline.Prospecting;

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

/**
 * Conversion, driven over the boundary: a qualified lead arrives as a {@code Map} (the two spread layers
 * flat, the campaign set as an array, the lead source as one leaf tag), and what comes back is either a
 * converted lead with an account and a contact, or every reason it was blocked.
 *
 * <p>The load-bearing checks are that the contact's reach is the lead's reach unchanged — conversion
 * carries the address rather than rebuilding it, so the format rule cannot drift between the two sides —
 * and that a blocked conversion reports both of its reasons rather than the first. What is deliberately
 * absent is a second-conversion test: {@code convertLead} takes a {@code QualifiedLead} and returns a
 * {@code ConvertedLead}, so converting twice is not a call that compiles. There is nothing to assert.
 *
 * <p>The injected {@code findAccountByDomain} is bound here from plain Java with no framework, which is
 * all an injected behavior needs. Its rule was already pinned at compile time by {@code fake} +
 * {@code example} in crm.sou; what this adds is the path a real implementation takes — success through
 * the generated decoder, failure through the inherited factory.
 */
class LeadConversionTest {

    @Test
    void aCleanConversionCarriesTheLeadsAddressAndDerivesTheAccountsDomain() {
        ConvertLeadResult result = ConvertLead.of().apply(
                qualifiedLead(true), request("2026-07-20", "2026-09-30"), emptyAccounts(), emptyContacts());

        LeadConverted converted = assertInstanceOf(LeadConverted.class, result);
        Map<String, Object> encoded = LeadConverted.encoder().encode(converted);

        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) encoded.get("contact");
        @SuppressWarnings("unchecked")
        Map<String, Object> reach = (Map<String, Object>) contact.get("reach");
        assertEquals("EmailAndPhone", reach.get("type"), "the reach is carried, case and all");
        assertEquals("cto@acme.example.com", reach.get("email"));

        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) encoded.get("account");
        assertEquals("acme.example.com", account.get("domain"), "the domain is derived from the address");
        assertEquals("Acme Corp", account.get("name"), "the account takes the lead's company name");

        // The intent travels with the result, so the pipeline can decide without re-reading the lead.
        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) encoded.get("intent");
        assertEquals("OpportunityRequested", intent.get("type"));
    }

    @Test
    void everyBlockingReasonIsReportedAtOnce() {
        // The budget is unconfirmed AND the close date is behind the conversion date. A CRM that reported
        // one of these would send the user round the loop twice.
        ConvertLeadResult result = ConvertLead.of().apply(
                qualifiedLead(false), request("2026-07-20", "2026-07-01"), emptyAccounts(), emptyContacts());

        ConversionBlocked blocked = assertInstanceOf(ConversionBlocked.class, result);
        @SuppressWarnings("unchecked")
        List<Object> reasons = (List<Object>) ConversionBlocked.encoder().encode(blocked).get("reasons");
        assertEquals(2, reasons.size(), reasons.toString());
        assertEquals("BudgetNotConfirmed", ((Map<?, ?>) reasons.get(0)).get("type"));
        assertEquals("CloseDateInPast", ((Map<?, ?>) reasons.get(1)).get("type"));
    }

    @Test
    void anAccountAlreadyOnTheDomainBlocksTheConversionAndNamesIt() {
        ConvertLeadResult result = ConvertLead.of().apply(
                qualifiedLead(true), request("2026-07-20", "2026-09-30"),
                accountsOn("acme.example.com"), emptyContacts());

        ConversionBlocked blocked = assertInstanceOf(ConversionBlocked.class, result);
        @SuppressWarnings("unchecked")
        List<Object> reasons = (List<Object>) ConversionBlocked.encoder().encode(blocked).get("reasons");
        assertEquals(1, reasons.size(), reasons.toString());
        assertEquals("DuplicateAccount", ((Map<?, ?>) reasons.get(0)).get("type"));
        assertEquals("001000000000002", ((Map<?, ?>) reasons.get(0)).get("existing"));
    }

    @Test
    void anInjectedLookupIsBoundFromPlainJava() {
        LinkLeadToAccount link = LinkLeadToAccount.bind(
                new InMemoryFindAccountByDomain(Map.of("beta.example.com", account("beta.example.com"))));

        MatchedAccount matched = assertInstanceOf(MatchedAccount.class, link.apply(newLead("h@beta.example.com")));
        assertEquals("001000000000002", matched.accountId().value());

        assertInstanceOf(UnmatchedLead.class, link.apply(newLead("s@gamma.example.com")));
        assertInstanceOf(NoEmailOnLead.class, link.apply(phoneOnlyLead()));
    }

    @Test
    void theThirdLegOfConversionIsBuiltByTheContextThatOwnsIt() {
        // This is the CRM-to-SFA seam, and it is two calls with a Java line between them rather than one
        // composition: a cross-module `>->` would put crm's departed cases into a union example.pipeline
        // declares, which the compiler refuses. So the account and the contact are built here, the
        // opportunity is built there, and the boundary is what joins them.
        LeadConverted converted = assertInstanceOf(LeadConverted.class, ConvertLead.of().apply(
                qualifiedLead(true), request("2026-07-20", "2026-09-30"), emptyAccounts(), emptyContacts()));

        OpenFromLead open = OpenFromLead.bind(new SequentialOpportunityIds());
        Prospecting opened = assertInstanceOf(Prospecting.class, open.apply(converted));

        assertEquals("006000000000001", opened.id().value());
        assertEquals("Acme Corp — New Business", opened.name().value(),
                "the name is built from the account's, not typed in");
        assertEquals("001000000000100", opened.accountId().value());

        // A conversion that asked for no opportunity gets pipeline's own case back. crm's NoOpportunity
        // went in; NoOpportunityRequested came out, because a context that declines states its own
        // declining.
        LeadConverted filedOnly = assertInstanceOf(LeadConverted.class, ConvertLead.of().apply(
                qualifiedLead(true), requestWithoutOpportunity(), emptyAccounts(), emptyContacts()));
        assertInstanceOf(NoOpportunityRequested.class, open.apply(filedOnly));
    }

    /**
     * The zero-argument injected behavior — the one shape that produces rather than transforms, so its
     * generated base declares {@code apply()} with no input at all. The id comes from the protected factory
     * the base inherits, which is the only way a non-public constructor is reachable.
     */
    static final class SequentialOpportunityIds extends NextOpportunityId {

        private int minted = 0;

        @Override
        public OpportunityId apply() {
            minted = minted + 1;
            return OpportunityId(String.format("006%012d", minted));
        }
    }

    /**
     * The injected lookup, implemented over a map. The success value comes back through the generated
     * decoder because an implementation is not granted the constructor; the failure comes from the
     * factory the base class inherits for the unit case its declaration constructs. A platform failure
     * would be thrown rather than returned — this behavior has no case for one, on purpose.
     */
    static final class InMemoryFindAccountByDomain extends FindAccountByDomain {

        private final Map<String, Map<String, Object>> rows;

        InMemoryFindAccountByDomain(Map<String, Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public FindAccountByDomainResult apply(DomainName domain) {
            Map<String, Object> row = rows.get(domain.value());
            if (row == null) {
                return AccountNotFound();
            }
            return switch (Account.decoder().decode(row, Path.ROOT)) {
                case Ok<Account> ok -> ok.value();
                case Err<Account> e -> throw new IllegalStateException("stored account is not one: " + e.issues().asList());
            };
        }
    }

    private static QualifiedLead qualifiedLead(boolean budgetConfirmed) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "00Q000000000001");
        raw.put("person", "Aoyagi");
        raw.put("company", "Acme Corp");
        raw.put("title", "CTO");
        raw.put("reach", Map.of("type", "EmailAndPhone",
                "email", "cto@acme.example.com", "phone", "+81-3-1234-5678"));
        // The source is one flat leaf tag even though LeadSource nests two levels: "Referral" never
        // appears in the JSON.
        raw.put("source", Map.of("type", "PartnerReferral", "partner", "001000000000009"));
        raw.put("campaigns", List.of("Q3 Expo"));
        raw.put("owner", "u-001");
        raw.put("createdOn", LocalDate.parse("2026-07-01"));
        raw.put("firstTouchedOn", LocalDate.parse("2026-07-03"));
        raw.put("touches", 3);
        raw.put("qualifiedOn", LocalDate.parse("2026-07-15"));
        raw.put("score", 72);
        raw.put("budgetConfirmed", budgetConfirmed);
        return ok(QualifiedLead.decoder().decode(raw, Path.ROOT));
    }

    private static ConversionRequest request(String convertedOn, String closeDate) {
        return ok(ConversionRequest.decoder().decode(Map.of(
                "accountId", "001000000000100",
                "contactId", "003000000000100",
                "convertedOn", LocalDate.parse(convertedOn),
                "intent", Map.of("type", "OpportunityRequested",
                        "amount", new java.math.BigDecimal("4800000.00"), "closeDate", LocalDate.parse(closeDate)),
                "accountType", Map.of("type", "Prospect"),
                "industry", "Technology",
                "employees", 420,
                "currency", "JPY"), Path.ROOT));
    }

    private static ConversionRequest requestWithoutOpportunity() {
        return ok(ConversionRequest.decoder().decode(Map.of(
                "accountId", "001000000000101",
                "contactId", "003000000000101",
                "convertedOn", LocalDate.parse("2026-07-20"),
                "intent", Map.of("type", "NoOpportunity"),
                "accountType", Map.of("type", "Prospect"),
                "industry", "Technology",
                "employees", 420,
                "currency", "JPY"), Path.ROOT));
    }

    private static NewLead newLead(String email) {
        return ok(NewLead.decoder().decode(Map.of(
                "id", "00Q000000000020",
                "person", "Hirano",
                "company", "Beta KK",
                "reach", Map.of("type", "EmailOnly", "email", email),
                "source", Map.of("type", "WebForm"),
                "campaigns", List.of(),
                "owner", "u-001",
                "createdOn", LocalDate.parse("2026-07-01")), Path.ROOT));
    }

    private static NewLead phoneOnlyLead() {
        return ok(NewLead.decoder().decode(Map.of(
                "id", "00Q000000000022",
                "person", "Kawakami",
                "company", "Delta Inc",
                "reach", Map.of("type", "PhoneOnly", "phone", "+81-3-9999-0000"),
                "source", Map.of("type", "PhoneInquiry"),
                "campaigns", List.of(),
                "owner", "u-002",
                "createdOn", LocalDate.parse("2026-07-01")), Path.ROOT));
    }

    private static Map<String, Object> account(String domain) {
        return Map.of(
                "id", "001000000000002",
                "name", "Beta KK",
                "domain", domain,
                "industry", "Manufacturing",
                "employees", 999,
                "type", Map.of("type", "Customer", "since", LocalDate.parse("2024-04-01")),
                "owner", "u-001",
                "currency", "JPY");
    }

    private static AccountBook emptyAccounts() {
        return ok(AccountBook.decoder().decode(List.of(), Path.ROOT));
    }

    private static AccountBook accountsOn(String domain) {
        return ok(AccountBook.decoder().decode(List.of(account(domain)), Path.ROOT));
    }

    private static ContactBook emptyContacts() {
        return ok(ContactBook.decoder().decode(List.of(), Path.ROOT));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
