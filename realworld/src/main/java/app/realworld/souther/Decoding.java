// The Java side of the Souther boundary: one exception and one helper. Nothing here names a domain
// type, so it lifts out of this example unchanged. It is the Java counterpart of issuetracker's
// Souther.kt — smaller, because Java has no Option-to-nullable gap to bridge and the generated
// output unions are already sealed interfaces a switch is checked against.
package app.realworld.souther;

import net.unit8.raoh.Err;
import net.unit8.raoh.Issues;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;
import net.unit8.raoh.decode.Decoder;

/**
 * Decoding at the boundary. A decoder is the only way an outside value becomes a domain value —
 * data constructors are not public — and it is where the invariants are checked on the way through.
 */
public final class Decoding {

    private Decoding() {
    }

    /**
     * A decoder refused outside input. This is not a domain case: the domain never saw the value, so
     * nothing in a {@code .sou} declared an outcome for it. It is an exception the boundary maps to
     * 422, carrying raoh's issues with their paths and codes intact.
     */
    public static final class DecodeFailed extends RuntimeException {

        private final transient Issues issues;

        public DecodeFailed(Issues issues) {
            super(issues.toString());
            this.issues = issues;
        }

        public Issues issues() {
            return issues;
        }
    }

    /** Decodes from the root path, or fails the request. */
    public static <I, T> T decodeOrFail(Decoder<I, T> decoder, I input) {
        Result<T> result = decoder.decode(input, Path.ROOT);
        return switch (result) {
            case Ok<T> ok -> ok.value();
            case Err<T> err -> throw new DecodeFailed(err.issues());
        };
    }
}
