package example.crm;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derived discriminated codec, which is what the module this example grew out of was for. Nothing
 * in crm.sou mentions a decoder: the shape of the data is what generates one.
 *
 * <p>The load-bearing checks are that a sum round-trips through its {@code "type"} discriminator with
 * the payload of the case it landed on, that an unknown tag is a decode failure rather than a
 * silently-dropped field, and that a newtype's format invariant still runs when the value is reached
 * through the discriminator — including two layers down, through a product's field.
 */
class ContactPointCodecTest {

    @Test
    void aSumRoundTripsThroughItsDiscriminatorWithItsPayload() {
        Map<String, Object> raw = Map.of("type", "EmailAndPhone",
                "email", "ceo@acme.example.com", "phone", "+81-3-1234-5678");

        switch (ContactPoint.decoder().decode(raw)) {
            case Ok<ContactPoint> ok -> {
                Map<String, Object> encoded = ContactPoint.encoder().encode(ok.value());
                assertEquals("EmailAndPhone", encoded.get("type"), "the tag is the case name");
                // A product case lays its fields flat beside "type", so the JSON does not nest.
                assertEquals("ceo@acme.example.com", encoded.get("email"));
                assertEquals("+81-3-1234-5678", encoded.get("phone"));
            }
            case Err<ContactPoint>(var issues) -> throw new AssertionError("should decode: " + issues.asList());
        }
    }

    @Test
    void anUnknownDiscriminatorIsAnErrAtTheTopLevel() {
        assertInstanceOf(Err.class, ContactPoint.decoder().decode(Map.of("type", "Fax")));
        // No tag at all is a failure too: the discriminator is not optional, and a decoder does not
        // guess which case a bare object meant.
        assertInstanceOf(Err.class, ContactPoint.decoder().decode(Map.of("email", "a@example.com")));
    }

    @Test
    void aNewtypeInvariantInsideASumCaseIsEnforcedThroughTheDiscriminatedDecoder() {
        assertInstanceOf(Ok.class,
                ContactPoint.decoder().decode(Map.of("type", "PhoneOnly", "phone", "+81-3-1234-5678")));
        assertInstanceOf(Err.class,
                ContactPoint.decoder().decode(Map.of("type", "PhoneOnly", "phone", "1234")));
        assertInstanceOf(Err.class,
                ContactPoint.decoder().decode(Map.of("type", "EmailOnly", "email", "no-at-sign")));
    }

    @Test
    void theSameInvariantRunsTwoLayersDownThroughAProductField() {
        Result<Contact> bad = Contact.decoder().decode(Map.of(
                "id", "003000000000001",
                "accountId", "001000000000001",
                "name", "Aoyagi",
                "reach", Map.of("type", "EmailOnly", "email", "no-at-sign")));

        switch (bad) {
            case Err<Contact>(var issues) -> assertTrue(issues.asList().size() >= 1,
                    "the malformed address inside the sum inside the product is reported");
            case Ok<Contact> ok -> throw new AssertionError("should not decode: " + ok.value());
        }
    }

    @Test
    void independentFieldFailuresAccumulate() {
        // Two fields are wrong and both are reported: decoding is applicative, so a boundary can tell
        // the caller everything at once rather than one thing per round trip.
        Result<Contact> bad = Contact.decoder().decode(Map.of(
                "id", "003-not-an-id",
                "accountId", "001000000000001",
                "name", "Aoyagi",
                "reach", Map.of("type", "EmailOnly", "email", "no-at-sign")));

        switch (bad) {
            case Err<Contact>(var issues) -> assertEquals(2, issues.asList().size(), issues.asList().toString());
            case Ok<Contact> ok -> throw new AssertionError("should not decode: " + ok.value());
        }
    }

    @Test
    void anOptionalFieldIsOmittedWhenItIsNotThere() {
        // title is an honest optional: absent on the way in, absent on the way out, and no stand-in
        // value invented in between.
        Map<String, Object> raw = Map.of(
                "id", "003000000000001",
                "accountId", "001000000000001",
                "name", "Aoyagi",
                "reach", Map.of("type", "PhoneOnly", "phone", "+81-3-1234-5678"));

        Map<String, Object> encoded = Contact.encoder().encode(ok(Contact.decoder().decode(raw)));
        assertEquals(null, encoded.get("title"), "None omits the key rather than writing a null");
        assertEquals("PhoneOnly", ((Map<?, ?>) encoded.get("reach")).get("type"));
    }

    @Test
    void aNestedSumFoldsToItsLeafTag() {
        // LeadSource is a sum of sums: PartnerReferral is a case of Referral, which is a case of
        // LeadSource. The codec dispatches over the leaves, so the JSON carries one tag and it is the
        // leaf's — "Referral" never appears, and a boundary never has to unwrap two layers to learn what
        // a lead's source was.
        Map<String, Object> raw = Map.of("type", "PartnerReferral", "partner", "001000000000009");

        Map<String, Object> encoded = LeadSource.encoder().encode(ok(LeadSource.decoder().decode(raw)));
        assertEquals("PartnerReferral", encoded.get("type"));
        assertEquals("001000000000009", encoded.get("partner"));
        assertEquals(2, encoded.size(), "no wrapper layer for the sum in between: " + encoded);

        // The tag of an intermediate layer is not a tag anything answers to.
        assertInstanceOf(Err.class, LeadSource.decoder().decode(Map.of("type", "Referral")));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
