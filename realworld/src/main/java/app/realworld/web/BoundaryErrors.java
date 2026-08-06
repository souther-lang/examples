// The spec's error body, and the two failures that are not values.
//
// Almost nothing is handled here. A decoder's refusal is a Result the controller switches on, and a
// behavior's outcome is a case union it switches on too, so both are answered where they arrive
// rather than thrown past the route that asked for them. What is left is the two failures nobody
// declared: a request that named no viewer where one is required, and a database that is not
// answering. Neither is a case any .sou declared, and neither should be.
package app.realworld.web;

import net.unit8.raoh.Issue;
import net.unit8.raoh.Issues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class BoundaryErrors {

    private static final Logger LOG = LoggerFactory.getLogger(BoundaryErrors.class);

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
        // The response says nothing about the database, so the log has to: a 503 with no trace behind
        // it is the failure nobody can diagnose.
        LOG.error("the database refused a statement", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("errors", Map.of("body", List.of("database unavailable"))));
    }

    /** The spec's 422, built from messages a caller can act on. */
    public static ResponseEntity<Object> unprocessable(List<String> messages) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("errors", Map.of("body", messages)));
    }

    /**
     * The same body, built from everything a decoder refused at once. raoh accumulates rather than
     * stopping at the first issue, and each one carries the path that broke and the rule it broke, so
     * the message says which field rather than that something was wrong.
     */
    public static ResponseEntity<Object> unprocessable(Issues issues) {
        return unprocessable(issues.asList().stream().map(BoundaryErrors::describe).toList());
    }

    private static String describe(Issue issue) {
        String where = issue.path().toString();
        String what = issue.message() == null ? issue.code() : issue.message();
        return where.isEmpty() ? what : where + " " + what;
    }
}
