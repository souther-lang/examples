package blog.identity;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import souther.runtime.Option;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The derived decoder seen directly, with no Spring boundary in front of it: the module has no
 * decoder written by hand, so what is checked here is that {@code Username}'s and {@code Email}'s
 * invariants are enforced at the boundary and that {@code Profile}'s optional fields decode to
 * {@code Option.None} when the JSON leaves them out.
 */
class IdentityDecodeTest {

    @Test
    void aProfileWithNoBioOrImageDecodesWithBothFieldsAbsent() {
        Profile profile = ok(Profile.decoder().decode(Map.of("username", "jake")));
        assertInstanceOf(Option.None.class, profile.bio());
        assertInstanceOf(Option.None.class, profile.image());
    }

    @Test
    void anEmptyUsernameFailsTheLengthInvariant() {
        assertInstanceOf(Err.class,
                Profile.decoder().decode(Map.of("username", "")));
    }

    @Test
    void aStringWithNoAtSignIsNotAnEmail() {
        assertInstanceOf(Err.class,
                Email.decoder().decode("not-an-email"));
    }

    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("should decode: " + e.issues().asList());
        };
    }
}
