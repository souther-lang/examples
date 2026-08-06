package example.inventory;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Runs inventory.sou's {@code inspectBarcode} over the domain: a valid EAN-13 passes, a wrong check
 * digit is a scan error. This exercises the generated decode / apply / encode path (the check-digit
 * test is {@code List.indexedMap} + {@code List.sum} over the digits). The module's other behaviors
 * are checked by their {@code example}s at compile time.
 */
class InventoryTest {

    private Barcode barcode(String raw) {
        Result<Barcode> r = Barcode.decoder().decode(raw);
        if (r instanceof Err<Barcode> e) {
            throw new AssertionError("should decode: " + e.issues().asList());
        }
        return ((Ok<Barcode>) r).value();
    }

    @Test
    void aValidBarcodePassesInspection() {
        Object result = InspectBarcode.of().apply(barcode("9784873115658"));

        InspectionPassed passed = assertInstanceOf(InspectionPassed.class, result);
        assertEquals("9784873115658", InspectionPassed.encoder().encode(passed).get("code"));
    }

    @Test
    void aWrongCheckDigitIsAScanError() {
        assertInstanceOf(ScanError.class, InspectBarcode.of().apply(barcode("9784873115659")));
    }

    /** {@code baySlots} builds shelf codes rather than checking ones that came in: {@code List.range}
     *  gives the four levels of the bay and {@code String.padLeft} widens each number to the two
     *  digits {@code Location}'s invariant demands. */
    @Test
    void aBayNamesItsFourShelfCodes() {
        BayCandidates candidates = BaySlots.of().apply("A", 3L);

        assertEquals(java.util.List.of("A-03-01", "A-03-02", "A-03-03", "A-03-04"),
                BayCandidates.encoder().encode(candidates).get("locations"));
    }

    /** A bay number the format cannot hold is rejected where the code is built — the same invariant
     *  that rejects a malformed code arriving from outside. */
    @Test
    void aBayPastTheFormatIsRejectedAtConstruction() {
        org.junit.jupiter.api.Assertions.assertThrows(souther.runtime.ConstraintViolation.class,
                () -> BaySlots.of().apply("A", 100L));
    }

    /** The unit survives the boundary. {@code Eaches} and {@code Cases} are separate generated types,
     *  so the Java that drives the warehouse cannot hand one where the other belongs either — and
     *  each still encodes to the bare number a newtype is. */
    @Test
    void aQuantityCarriesItsUnitAcrossTheBoundary() {
        Object shipped = ToCases.of().apply(eaches(24), packSize(12));

        Shippable whole = assertInstanceOf(Shippable.class, shipped);
        assertEquals(2L, Shippable.encoder().encode(whole).get("cases"));
        assertEquals(24L, Eaches.encoder().encode(ToEaches.of().apply(cases(2), packSize(12))));
    }

    /** A quantity that is not a multiple of the pack leaves a remainder, and the remainder is counted
     *  in units — the failure case says which unit without anybody having to ask. */
    @Test
    void aRemainderIsReportedInUnits() {
        Object shipped = ToCases.of().apply(eaches(25), packSize(12));

        PartialCase partial = assertInstanceOf(PartialCase.class, shipped);
        assertEquals(1L, PartialCase.encoder().encode(partial).get("leftover"));
    }

    private Eaches eaches(long n) {
        return ((Ok<Eaches>) Eaches.decoder().decode(n)).value();
    }

    private Cases cases(long n) {
        return ((Ok<Cases>) Cases.decoder().decode(n)).value();
    }

    private PackSize packSize(long n) {
        return ((Ok<PackSize>) PackSize.decoder().decode(n)).value();
    }
}
