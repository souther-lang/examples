// The failures that are not domain outcomes, mapped in one place.
//
// A domain outcome is a case value a behavior declared, and each controller folds its own with a
// switch the compiler checks for exhaustiveness. What is left over is here: input the decoder
// refused, a request with no viewer where one is required, and a database that is not answering.
// None of those is a case any .sou declared, and none of them should be.
package app.realworld.web;

import app.realworld.souther.Decoding.DecodeFailed;

import net.unit8.raoh.Issue;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class BoundaryErrors {

    /**
     * The spec's error body: {@code {"errors": {"body": ["...", ...]}}} at 422. raoh's issues carry
     * the path that broke and the rule it broke, so the message says which field rather than that
     * something was wrong.
     */
    @ExceptionHandler(DecodeFailed.class)
    public ResponseEntity<Object> onDecodeFailure(DecodeFailed e) {
        List<String> messages = e.issues().asList().stream()
                .map(BoundaryErrors::describe)
                .toList();
        return unprocessable(messages);
    }

    /** A required-auth endpoint reached without a viewer. */
    @ExceptionHandler(Viewer.Unauthenticated.class)
    public ResponseEntity<Object> onUnauthenticated(Viewer.Unauthenticated e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /**
     * A platform failure: an exception the jOOQ binding threw and Souther passed through. The domain
     * declared no case for a database being down because it is not a business answer (ADR-0029).
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> onPlatformFailure(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("errors", Map.of("body", List.of("database unavailable"))));
    }

    /** The spec's 422, built from messages a caller can act on. */
    public static ResponseEntity<Object> unprocessable(List<String> messages) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("errors", Map.of("body", messages)));
    }

    private static String describe(Issue issue) {
        String where = issue.path().toString();
        String what = issue.message() == null ? issue.code() : issue.message();
        return where.isEmpty() ? what : where + " " + what;
    }
}
