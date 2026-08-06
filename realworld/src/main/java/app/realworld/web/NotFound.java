// A name in the path with nothing behind it.
//
// This is not a domain case, and no .sou declares one for it. A behavior answers ArticleNotFound or
// UserNotFound to whoever asked it a question; what the boundary has here is a request that never got
// as far as asking, because the thing it names does not exist. The domain has no opinion on that, so
// it is an exception rather than a case that would have to be threaded through every route.
package app.realworld.web;

/** Mapped to 404 by {@link BoundaryErrors}. */
public final class NotFound extends RuntimeException {
}
