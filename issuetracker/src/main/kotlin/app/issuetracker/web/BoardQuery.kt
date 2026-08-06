// The read side. countByLabel and topLabels are pure behaviors over a whole Board, so something has to
// assemble that Board from storage — and unlike a label change, a summary makes no decision that the
// domain needs to be in on. So it is not an injected behavior: the boundary reads the rows and builds
// the Board through the derived decoder, then hands it to the pure behavior.
package app.issuetracker.web

import app.issuetracker.issueRows

import example.issuetracker.Board

import org.jooq.DSLContext
import org.springframework.stereotype.Component

@Component
class BoardQuery(private val dsl: DSLContext) {

    /**
     * Every stored issue as one Board. What this decoder reads is rows this service wrote, not anything
     * a caller sent, so a refusal is storage holding something the domain cannot read — a fault rather
     * than an answer to a request, and `getOrThrow` is what says so.
     */
    fun board(): Board =
        Board.decoder().decode(mapOf("issues" to dsl.issueRows())).getOrThrow()
}
