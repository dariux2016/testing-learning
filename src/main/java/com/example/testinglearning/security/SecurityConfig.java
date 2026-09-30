package com.example.testinglearning.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP-level security for the order API (see docs/testing-strategy.md §8): a stateless JWT resource
 * server whose tokens are issued by Keycloak ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}).
 *
 * <p>Only the rules that depend on nothing but the URL and the caller's role live here. Rules that
 * need the <em>data</em> ("is this your order?") are {@code @PreAuthorize}/{@code @PostAuthorize}
 * annotations on {@code OrderService}, enabled by {@link MethodSecurityConfig}.
 */
@Configuration
// Only a servlet web app has an HTTP filter chain to configure. Without this, a non-web context
// (e.g. the Kafka integration tests, spring.main.web-application-type=none) fails at startup
// because there's no HttpSecurity bean.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Warehouse/back-office actions. Customers never pay or ship by hand:
                        // payments normally arrive over Kafka (PaymentConfirmationListener).
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/pay", "/api/orders/*/ship").hasRole("STAFF")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Every request carries its own bearer token, so there's no server-side session.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF works by abusing the browser's habit of attaching cookies to cross-site
                // requests automatically. A bearer token is never attached automatically, so a
                // cookie-less API has nothing for CSRF to exploit. OrderControllerSecuritySliceTest
                // pins down that this stays true (no CSRF token needed, no session cookie issued).
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Keycloak realm roles become {@code ROLE_*} authorities, and {@code authentication.getName()}
     * is the token's {@code email} claim rather than its opaque {@code sub}. The ownership rules on
     * {@code OrderService} compare it with {@code Order.customerEmail}.
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("email");
        return converter;
    }
}
