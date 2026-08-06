package example.catalog;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the {@code example}s in catalog.sou cannot show: that the codec derived for a self-referential
 * {@code data} traverses it. A {@code Component} holds a {@code List<Component>}, and the decoder
 * built from that shape reads a structure three levels deep out of plain nested maps and lists —
 * no hand-written traversal, and the invariant on every node runs on the way in.
 */
class CatalogTest {

    /** A desk of four leg sets, each of eight bolts and a foot, plus a top. */
    private static Map<String, Object> deskJson() {
        return Map.of("sku", "desk", "quantity", 1L, "parts", List.of(
                Map.of("sku", "leg-set", "quantity", 4L, "parts", List.of(
                        Map.of("sku", "bolt", "quantity", 8L, "parts", List.of()),
                        Map.of("sku", "foot", "quantity", 1L, "parts", List.of()))),
                Map.of("sku", "top", "quantity", 1L, "parts", List.of())));
    }

    private static Component decode(Map<String, Object> json) {
        Result<Component> r = Component.decoder().decode(json);
        if (r instanceof Err<Component> e) {
            throw new AssertionError("should decode: " + e.issues().asList());
        }
        return ((Ok<Component>) r).value();
    }

    @Test
    void aStructureThreeLevelsDeepDecodes() {
        Component desk = decode(deskJson());

        assertEquals(2, desk.parts().size());
        assertEquals("bolt", desk.parts().get(0).parts().get(0).sku().value());
    }

    /** Encoding is the same walk the other way, so the nested shape comes back as it went in. */
    @Test
    void andEncodesBackToTheSameNesting() {
        assertEquals(deskJson(), Component.encoder().encode(decode(deskJson())));
    }

    /** A quantity of zero fails {@code Component}'s invariant, and it fails at the node that holds
     *  it — the issue's path names the leg set's bolts rather than the desk. */
    @Test
    void anInvariantDeepInTheStructureIsReportedWhereItSits() {
        Map<String, Object> broken = Map.of("sku", "desk", "quantity", 1L, "parts", List.of(
                Map.of("sku", "leg-set", "quantity", 0L, "parts", List.of())));

        Result<Component> r = Component.decoder().decode(broken);

        Err<Component> err = (Err<Component>) r;
        assertEquals("/parts/0", err.issues().asList().get(0).path().toString());
    }

    /** The requirement over the decoded structure: every level multiplied through, and the leaves
     *  told apart from what is made. */
    @Test
    void theWholeStructureExplodesToOneRequirement() {
        Requirement requirement = Explode.of().apply(decode(deskJson()), 2L);

        Map<String, Object> encoded = Requirement.encoder().encode(requirement);
        assertEquals(Map.of("desk", 2L, "leg-set", 8L, "bolt", 64L, "foot", 8L, "top", 2L),
                encoded.get("need"));
        // A Set encodes to a JSON array in the order it happens to hold, which is not an order the
        // domain states, so the assertion is about membership and not about the array.
        assertEquals(java.util.Set.of("bolt", "foot", "top"),
                java.util.Set.copyOf((List<?>) encoded.get("purchased")));
    }
}
