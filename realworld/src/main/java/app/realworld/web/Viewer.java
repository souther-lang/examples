// Who is asking. Six of the API's endpoints answer differently depending on the viewer and four more
// refuse without one, so it is a controller parameter rather than something each handler digs out of
// a header.
package app.realworld.web;

import example.identity.Username;

import java.util.Optional;

/**
 * The authenticated username, if the request carried a valid token. Absent covers every way a request
 * can fail to name somebody — no header, the wrong scheme, a bad signature, an expired token — because
 * none of them is a different answer: the request has no viewer.
 *
 * <p>Whether that is a 401 is the endpoint's decision. {@code GET /api/profiles/{username}} works
 * without a viewer and reports {@code following: false}; {@code GET /api/user} does not.
 */
public record Viewer(Optional<Username> username) {

    public static final Viewer ANONYMOUS = new Viewer(Optional.empty());

    public boolean isPresent() {
        return username.isPresent();
    }

    /** The viewer, or the exception the boundary turns into 401. */
    public Username required() {
        return username.orElseThrow(Unauthenticated::new);
    }

    /** A required-auth endpoint reached with no viewer. */
    public static final class Unauthenticated extends RuntimeException {
    }
}
