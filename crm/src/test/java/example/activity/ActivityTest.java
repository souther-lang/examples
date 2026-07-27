package example.activity;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What the activity rules look like at the boundary. The rules themselves are pinned by the {@code example}
 * rows in activity.sou, which run at compile time; what a test adds is the shape the answers take once they
 * cross out of the model — a {@code Set} as an array with no duplicates, a {@code Map} keyed by a date as its
 * ISO form, and a {@code Map} keyed by a String-backed newtype as bare strings.
 */
class ActivityTest {

    @Test
    void aSetOfAttendeesCrossesAsAnArrayWithEachPersonOnce() {
        Coverage coverage = MultiThreaded.of().apply(List.of(
                meeting("00T000000000004", "2026-07-23T13:00:00", 90, List.of("003000000000100")),
                meeting("00T000000000005", "2026-07-25T13:00:00", 30,
                        List.of("003000000000100", "003000000000200"))));

        Map<String, Object> encoded = Coverage.encoder().encode(coverage);
        @SuppressWarnings("unchecked")
        List<String> contacts = (List<String>) encoded.get("contacts");
        assertEquals(2, contacts.size(), "the person in both rooms is one person: " + contacts);
        assertEquals(false, encoded.get("singleThreaded"));
    }

    @Test
    void aDateKeyedMapCrossesAsItsIsoForm() {
        DayLoad load = DayLoadOf.of().apply(List.of(
                meeting("00T000000000004", "2026-07-23T13:00:00", 90, List.of("003000000000100")),
                meeting("00T000000000005", "2026-07-23T16:00:00", 30, List.of("003000000000100")),
                meeting("00T000000000006", "2026-07-24T09:00:00", 60, List.of("003000000000100"))),
                user("u-001"));

        Map<String, Object> encoded = DayLoad.encoder().encode(load);
        @SuppressWarnings("unchecked")
        Map<String, Object> perDay = (Map<String, Object>) encoded.get("minutesPerDay");
        assertEquals(120L, ((Number) perDay.get("2026-07-23")).longValue(), "two meetings on one day add up");
        assertEquals(60L, ((Number) perDay.get("2026-07-24")).longValue());
        assertEquals(120L, ((Number) encoded.get("busiestMinutes")).longValue());
    }

    @Test
    void aDayWithNoMeetingsHasALoadAndItIsZero() {
        // The one place a default is the right answer rather than a case: a person with no meetings has a
        // load, and it is nothing. Compare with NeverTouched, where absence means something else entirely.
        DayLoad load = DayLoadOf.of().apply(List.of(), user("u-001"));
        assertEquals(0L, ((Number) DayLoad.encoder().encode(load).get("busiestMinutes")).longValue());
    }

    @Test
    void anAccountKeyedMapCrossesAsBareStrings() {
        StaleReport report = StaleAccounts.of().apply(
                List.of(call("00T000000000001", "2026-07-01", "001000000000100"),
                        call("00T000000000002", "2026-07-25", "001000000000100"),
                        call("00T000000000003", "2026-06-01", "001000000000200")),
                LocalDate.parse("2026-07-30"), cadence(14));

        Map<String, Object> encoded = StaleReport.encoder().encode(report);
        @SuppressWarnings("unchecked")
        Map<String, Object> since = (Map<String, Object>) encoded.get("daysSince");
        assertEquals(5L, ((Number) since.get("001000000000100")).longValue(), "the freshest touch, not the sum");
        assertEquals(59L, ((Number) since.get("001000000000200")).longValue());
        assertEquals(List.of("001000000000200"), encoded.get("stale"), "only one is past the cadence");
    }

    @Test
    void anUntouchedAccountHasNoRecencyAtAll() {
        assertInstanceOf(NeverTouched.class,
                LastActivityOn.of().apply(List.of(), LocalDate.parse("2026-07-30")));

        Recency recency = assertInstanceOf(Recency.class, LastActivityOn.of().apply(
                List.of(call("00T000000000001", "2026-07-25", "001000000000100")),
                LocalDate.parse("2026-07-30")));
        assertEquals(5, recency.daysSince());
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static Meeting meeting(String id, String startAt, int minutes, List<String> attendees) {
        return ok(Meeting.decoder().decode(Map.of(
                "id", id,
                "subject", "Workshop",
                "owner", "u-001",
                "related", Map.of("type", "RelatedToAccount", "accountId", "001000000000100"),
                "startAt", LocalDateTime.parse(startAt),
                "minutes", minutes,
                "attendees", attendees), Path.ROOT));
    }

    private static CallTask call(String id, String dueOn, String accountId) {
        return ok(CallTask.decoder().decode(Map.of(
                "id", id,
                "subject", "Check in",
                "owner", "u-001",
                "related", Map.of("type", "RelatedToAccount", "accountId", accountId),
                "dueOn", LocalDate.parse(dueOn),
                "outcome", Map.of("type", "Connected", "minutes", 20)), Path.ROOT));
    }

    private static example.crm.UserId user(String id) {
        return ok(example.crm.UserId.decoder().decode(id, Path.ROOT));
    }

    private static CadenceDays cadence(int days) {
        return ok(CadenceDays.decoder().decode((long) days, Path.ROOT));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
