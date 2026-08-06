package example.whodunit;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Drives whodunit.sou from the boundary. Clues arrive as neutral maps — the derived decoder reads
 * the {@code Clue} sum by its case name, and a unit case like a suspect crosses as a bare string —
 * and {@code solve} answers with a case of its output union.
 */
class WhodunitTest {

    private static Clue clue(Map<String, Object> raw) {
        return switch (Clue.decoder().decode(raw)) {
            case Ok<Clue> ok -> ok.value();
            case Err<Clue> err -> throw new AssertionError("decode failed: " + err.issues());
        };
    }

    @Test
    void aPinnedDownSceneNamesTheCulprit() {
        List<Clue> clues = List.of(
                clue(Map.of("type", "CulpritWasIn", "room", "Kitchen")),
                clue(Map.of("type", "SawIn", "who", "Alice", "room", "Study")),
                clue(Map.of("type", "WasElsewhere", "who", "Bob", "room", "Kitchen")));

        Unraveled verdict = assertInstanceOf(Unraveled.class, Solve.of().apply(clues));

        assertEquals("Carol", Unraveled.encoder().encode(verdict).get("culprit"));
    }

    @Test
    void aSceneAloneFitsTooManyStories() {
        assertInstanceOf(StillAmbiguous.class,
                Solve.of().apply(List.of(clue(Map.of("type", "CulpritWasIn", "room", "Kitchen")))));
    }
}
