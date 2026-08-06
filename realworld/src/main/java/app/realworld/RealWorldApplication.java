package app.realworld;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot's entry point, matching {@code app.ordering.OrderingApplication}: infra beans
 * (DataSource / DSLContext / TransactionManager) are wired explicitly in later tasks rather than
 * left to autoconfig.
 *
 * <p>{@code proxyBeanMethods = false} drops the CGLIB proxy on {@code @Configuration} classes.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class RealWorldApplication {
    public static void main(String[] args) {
        SpringApplication.run(RealWorldApplication.class, args);
    }
}
