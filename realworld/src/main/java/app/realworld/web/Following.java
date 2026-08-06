// Who the viewer follows, read once per request.
//
// `following` appears on every author the API ever returns — on a profile, on an article's author, on
// a comment's. It is a fact about the viewer rather than about the author, so no .sou holds it, and
// the boundary has to work it out. Doing that per author would be one query per row on every listing;
// the followee set is one query for the whole request, and membership is then free.
package app.realworld.web;

import example.identity.Followees;
import example.identity.ReadFollowees;
import example.identity.Username;

import java.util.Set;

public final class Following {

    private final ReadFollowees readFollowees;

    public Following(ReadFollowees readFollowees) {
        this.readFollowees = readFollowees;
    }

    /** The viewer's followees, or nothing at all when the request named nobody. */
    public Set<Username> of(Viewer viewer) {
        return viewer.username()
                .map(readFollowees::apply)
                .map(Followees::usernames)
                .orElseGet(Set::of);
    }
}
