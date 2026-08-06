// Resolves the Viewer parameter from the Authorization header.
package app.realworld.web;

import blog.identity.Username;

import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * The RealWorld spec's scheme is {@code Authorization: Token <jwt>} — not {@code Bearer}, which is
 * the mistake that makes a conforming frontend fail against an otherwise correct backend. Anything
 * else yields an anonymous viewer.
 */
public final class ViewerArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String SCHEME = "Token ";

    private final JwtTokens tokens;

    public ViewerArgumentResolver(JwtTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Viewer.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mav,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) {
            return Viewer.ANONYMOUS;
        }
        return tokens.usernameOf(header.substring(SCHEME.length()).trim())
                .flatMap(ViewerArgumentResolver::asUsername)
                .map(name -> new Viewer(Optional.of(name)))
                .orElse(Viewer.ANONYMOUS);
    }

    /**
     * The subject still has to meet Username's invariant. A token this service signed always will;
     * checking anyway is what keeps the only route into a domain type the decoder, so a signing key
     * that ever leaked could not put an unrepresentable name into one.
     */
    private static Optional<Username> asUsername(String subject) {
        return Username.decoder().decode(subject, Path.ROOT) instanceof Ok<Username> ok
                ? Optional.of(ok.value())
                : Optional.empty();
    }
}
