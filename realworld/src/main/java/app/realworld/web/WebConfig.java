// The MVC side of the boundary: what a controller may take as a parameter.
package app.realworld.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Makes {@link Viewer} a controller parameter, resolved from the Authorization header. */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final JwtTokens tokens;

    public WebConfig(JwtTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ViewerArgumentResolver(tokens));
    }

    /**
     * The spec requires this: a RealWorld frontend runs on its own origin, so every request it makes
     * is cross-origin and the ones carrying a token are preflighted. {@code Authorization} has to be
     * among the allowed headers or the browser never sends it, and the request arrives anonymous —
     * which looks like a broken login rather than a missing CORS header.
     *
     * <p>Any origin is allowed because this is an example somebody runs locally against whichever
     * frontend they cloned. A deployment would name its own.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }
}
