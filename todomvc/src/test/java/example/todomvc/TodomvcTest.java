package example.todomvc;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives todomvc.sou from the boundary, the same way WhodunitTest drives whodunit.sou: raw maps in,
 * the derived decoder reads them into {@code Todo} and {@code Filter}, and the two pure behaviors
 * answer. Nothing here touches storeTodo, readTodos, findTodo, renameTodo, setCompleted,
 * repositionTodo, removeTodo or deleteAllTodos — those are injected and have no body to drive; see
 * PROMPT.md for who implements them and against what.
 */
class TodomvcTest {

    private static Todo todo(Map<String, Object> raw) {
        return switch (Todo.decoder().decode(raw)) {
            case Ok<Todo> ok -> ok.value();
            case Err<Todo> err -> throw new AssertionError("decode failed: " + err.issues());
        };
    }

    private static Filter filter(String raw) {
        return switch (Filter.decoder().decode(raw)) {
            case Ok<Filter> ok -> ok.value();
            case Err<Filter> err -> throw new AssertionError("decode failed: " + err.issues());
        };
    }

    private static final Todo BUY_MILK =
            todo(Map.of("id", 1, "title", "Buy milk", "completed", false, "order", 0));
    private static final Todo WALK_DOG =
            todo(Map.of("id", 2, "title", "Walk the dog", "completed", true, "order", 1));
    private static final Todo PAY_RENT =
            todo(Map.of("id", 3, "title", "Pay rent", "completed", false, "order", 2));

    @Test
    void allShowsEverythingDoneOrNot() {
        List<Todo> visible = VisibleTodos.of().apply(List.of(BUY_MILK, WALK_DOG, PAY_RENT), filter("All"));
        assertEquals(List.of(BUY_MILK, WALK_DOG, PAY_RENT), visible);
    }

    @Test
    void activeHidesWhatIsAlreadyDone() {
        List<Todo> visible = VisibleTodos.of().apply(List.of(BUY_MILK, WALK_DOG, PAY_RENT), filter("Active"));
        assertEquals(List.of(BUY_MILK, PAY_RENT), visible);
    }

    @Test
    void completedKeepsOnlyWhatIsDone() {
        List<Todo> visible = VisibleTodos.of().apply(List.of(BUY_MILK, WALK_DOG, PAY_RENT), filter("Completed"));
        assertEquals(List.of(WALK_DOG), visible);
    }

    @Test
    void remainingCountsWhatActiveWouldShow() {
        assertEquals(2L, RemainingCount.of().apply(List.of(BUY_MILK, WALK_DOG, PAY_RENT)));
    }

    @Test
    void anEmptyListHasNothingLeft() {
        assertEquals(0L, RemainingCount.of().apply(List.of()));
    }
}
