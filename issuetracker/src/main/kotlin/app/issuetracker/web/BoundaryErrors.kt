// The one failure that is not a value.
//
// A decoder's refusal is not handled here. It is a Raoh Result the controller answers with a `when`,
// beside the case union the behavior answers, so both arrive where they were asked for. What is left is
// a platform failure, which is no case either (ADR-0029): the jOOQ implementation does not catch it,
// Souther passes it through untouched, and it arrives as a DataAccessException — 503. Spring Boot's jOOQ
// autoconfig enables exception translation, which is what turns jOOQ's own exception into that type.
package app.issuetracker.web

import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class BoundaryErrors {

    @ExceptionHandler(DataAccessException::class)
    fun onPlatformFailure(e: DataAccessException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("error" to "storage_unavailable"))
}
