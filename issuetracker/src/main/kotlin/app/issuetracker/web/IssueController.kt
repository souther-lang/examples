// The REST boundary. Every route is the same three steps: decode the outside values into domain values,
// call one behavior, fold its output union into a status and a body.
//
// Two things are worth looking at.
//
// A request body arrives as a plain Map and is handed to the *derived* decoder — there is no Kotlin data
// class mirroring the request shape. The shape is already declared in issues.sou, and the derived decoder
// is what checks the invariants (a Label is non-empty, and so on) and reports failures as Raoh issues with
// their JSON paths. A data class here would duplicate the domain shape and would reject a malformed body
// in Jackson, before the decoder that has the actual rules ever ran.
//
// A behavior's output is a generated `sealed` union, so `when` over it is exhaustive and the Kotlin
// compiler rejects a missing case by name. This is what account's `case-of` macro had to hand-build for
// Clojure: Souther's `match` totality carried across the boundary, here for free.
//
// A decoder's refusal is a `when` beside it. Raoh's `Result` is sealed too, so `Ok`/`Err` is checked the
// same way the case unions are, and nothing sits between the two: `IssueId.decoder().decode(id)` is the
// whole of the boundary's decoding. Turning that Result into an exception on the way in would have spent
// Raoh's accumulation before it was used — `shared` reads two ids, and a call that got both wrong is told
// about both because the two Results are combined rather than answered one at a time.
package app.issuetracker.web

import example.issuetracker.Assigned
import example.issuetracker.AssigneeOf
import example.issuetracker.AssigneeOfResult
import example.issuetracker.AttachLabel
import example.issuetracker.AttachLabelResult
import example.issuetracker.Board
import example.issuetracker.BusyLabels
import example.issuetracker.CountByLabel
import example.issuetracker.DetachLabel
import example.issuetracker.DetachLabelResult
import example.issuetracker.FindIssue
import example.issuetracker.FindIssueResult
import example.issuetracker.Issue
import example.issuetracker.IssueId
import example.issuetracker.IssueNotFound
import example.issuetracker.LabelCounts
import example.issuetracker.LabelRanking
import example.issuetracker.LabelRequest
import example.issuetracker.LabelSet
import example.issuetracker.LabelUsage
import example.issuetracker.NewIssue
import example.issuetracker.NoLabels
import example.issuetracker.OpenIssue
import example.issuetracker.OpenIssueResult
import example.issuetracker.SharedLabels
import example.issuetracker.TopLabels
import example.issuetracker.Unassigned

import net.unit8.raoh.Err
import net.unit8.raoh.Issues
import net.unit8.raoh.Ok
import net.unit8.raoh.Result

import souther.runtime.Behavior

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class IssueController(
    private val openIssue: OpenIssue,
    private val findIssue: FindIssue,
    private val attachLabel: AttachLabel,
    private val detachLabel: DetachLabel,
    private val assigneeOf: AssigneeOf,
    private val sharedLabels: SharedLabels,
    private val countByLabel: CountByLabel,
    private val topLabels: TopLabels,
    private val busyLabels: BusyLabels,
    private val boardQuery: BoardQuery,
) {

    /**
     * Opens an issue from a raw comma-separated label string. openIssue normalizes it, and reports
     * NoLabels when nothing survives — which is a domain outcome, so it is a case rather than an
     * exception, and it lands here as a returned value. Two rows are written (the issue and its labels),
     * hence the transaction.
     */
    @PostMapping("/issues")
    @Transactional
    fun open(@RequestBody body: Map<String, Any>): ResponseEntity<Any> =
        when (val decoded = NewIssue.decoder().decode(body)) {
            is Ok -> openIssue(decoded.value).toResponse()
            is Err -> badRequest(decoded.issues)
        }

    @GetMapping("/issues/{id}")
    fun find(@PathVariable id: String): ResponseEntity<Any> =
        when (val found = issue(id)) {
            is Ok -> found.value.toResponse()
            is Err -> badRequest(found.issues)
        }

    /** The optional assignee, opened by the domain: Some(Assignee(name)) is 200, None is 204. */
    @GetMapping("/issues/{id}/assignee")
    fun assignee(@PathVariable id: String): ResponseEntity<Any> =
        when (val found = issue(id)) {
            is Ok -> when (val result = found.value) {
                is Issue -> assigneeOf(result).toResponse()
                is IssueNotFound -> notFound()
            }
            is Err -> badRequest(found.issues)
        }

    /**
     * Adds a label. attachLabel reads the issue, inserts into its label Set and writes it back, so the
     * read and the write belong in one transaction — otherwise a concurrent call could drop a label by
     * writing back a set it read before the other one landed.
     */
    @PostMapping("/issues/{id}/labels")
    @Transactional
    fun attach(@PathVariable id: String, @RequestBody body: Map<String, Any>): ResponseEntity<Any> =
        // The id from the path goes into the body and the whole of it through one decoder, so the id
        // and the label are checked together and reported together.
        when (val request = LabelRequest.decoder().decode(body + ("id" to id))) {
            is Ok -> attachLabel(request.value).toResponse()
            is Err -> badRequest(request.issues)
        }

    @DeleteMapping("/issues/{id}/labels/{label}")
    @Transactional
    fun detach(@PathVariable id: String, @PathVariable label: String): ResponseEntity<Any> =
        when (val request = LabelRequest.decoder().decode(mapOf("id" to id, "label" to label))) {
            is Ok -> detachLabel(request.value).toResponse()
            is Err -> badRequest(request.issues)
        }

    /**
     * Set intersection over two issues. Either one missing is a 404. Both ids are read before either is
     * answered — `Result.map2` accumulates, so a call that got both wrong is told about both rather than
     * about whichever was decoded first. Each union is then narrowed to a Kotlin nullable by an
     * exhaustive fold (issueOrNull) rather than by a type test, so the totality stays the compiler's.
     */
    @GetMapping("/issues/{a}/shared-labels/{b}")
    fun shared(@PathVariable a: String, @PathVariable b: String): ResponseEntity<Any> {
        val found = Result.map2(issue(a), issue(b)) { first, second -> first to second }
        if (found is Err) return badRequest(found.issues)

        val (first, second) = (found as Ok).value
        val left = first.issueOrNull() ?: return notFound()
        val right = second.issueOrNull() ?: return notFound()
        return ResponseEntity.ok(LabelSet.encoder().encode(sharedLabels(left, right)))
    }

    /** How many issues carry each label, as a Map<String, Int> the encoder hands over as a JSON object. */
    @GetMapping("/labels/counts")
    fun counts(): Map<String, Any> =
        LabelCounts.encoder().encode(countByLabel(boardQuery.board()))

    @GetMapping("/labels/top")
    fun top(@RequestParam(defaultValue = "3") n: Long): Map<String, Any> =
        LabelRanking.encoder().encode(topLabels(boardQuery.board(), n))

    /** The labels at least `atLeast` issues carry, counts and all — Map.filter over the same counts. */
    @GetMapping("/labels/busy")
    fun busy(@RequestParam(defaultValue = "2") atLeast: Long): Map<String, Any> =
        LabelUsage.encoder().encode(busyLabels(boardQuery.board(), atLeast))

    /**
     * What the path names: the decoded id handed to the behavior that reads it. The two answers stay
     * apart — an Err is text that is not an id, and an IssueNotFound is an id with no issue behind it —
     * because the .sou declared the second one and nobody declared the first.
     */
    private fun issue(id: String): Result<FindIssueResult> =
        IssueId.decoder().decode(id).map { findIssue(it) }
}

// --- folding the output unions ---
// One `when` per union. The unions are distinct types (each behavior seals its own), so each gets its own
// fold, and each is checked for exhaustiveness on its own.

private fun OpenIssueResult.toResponse(): ResponseEntity<Any> = when (this) {
    is Issue -> ResponseEntity.status(HttpStatus.CREATED).body(Issue.encoder().encode(this))
    is NoLabels -> ResponseEntity.badRequest().body(mapOf("error" to "no_labels"))
}

private fun FindIssueResult.toResponse(): ResponseEntity<Any> = when (this) {
    is Issue -> ok(this)
    is IssueNotFound -> notFound()
}

private fun FindIssueResult.issueOrNull(): Issue? = when (this) {
    is Issue -> this
    is IssueNotFound -> null
}

private fun AttachLabelResult.toResponse(): ResponseEntity<Any> = when (this) {
    is Issue -> ok(this)
    is IssueNotFound -> notFound()
}

private fun DetachLabelResult.toResponse(): ResponseEntity<Any> = when (this) {
    is Issue -> ok(this)
    is IssueNotFound -> notFound()
}

private fun AssigneeOfResult.toResponse(): ResponseEntity<Any> = when (this) {
    is Assigned -> ResponseEntity.ok(Assigned.encoder().encode(this))
    is Unassigned -> ResponseEntity.noContent().build()
}

private fun ok(issue: Issue): ResponseEntity<Any> = ResponseEntity.ok(Issue.encoder().encode(issue))

private fun notFound(): ResponseEntity<Any> = ResponseEntity.notFound().build()

/**
 * What a decoder refused. Raoh accumulates rather than stopping at the first issue, and each one carries
 * the path that broke and the code of the rule it broke, so the body names the field. This is the only
 * file that answers a decode failure, which is why it is here rather than in BoundaryErrors.
 */
private fun badRequest(issues: Issues): ResponseEntity<Any> =
    ResponseEntity.badRequest().body(mapOf("issues" to issues.toJsonList()))

// Every behavior call at this boundary reads as a function call. `Behavior` is the unary contract, so a
// behavior of two arguments is not one and the three below get their own.
private operator fun <I : Any, O : Any> Behavior<I, O>.invoke(input: I): O = apply(input)
private operator fun SharedLabels.invoke(a: Issue, b: Issue): LabelSet = apply(a, b)

private operator fun TopLabels.invoke(board: Board, n: Long): LabelRanking = apply(board, n)

private operator fun BusyLabels.invoke(board: Board, atLeast: Long): LabelUsage = apply(board, atLeast)
