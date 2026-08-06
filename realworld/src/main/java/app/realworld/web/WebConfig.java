// The MVC side of the boundary: what a controller may take as a parameter.
package app.realworld.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
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
}
