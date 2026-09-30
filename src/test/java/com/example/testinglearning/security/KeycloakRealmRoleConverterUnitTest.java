package com.example.testinglearning.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for turning a Keycloak-shaped JWT into a Spring {@code Authentication} (see
 * docs/testing-strategy.md §8): {@link KeycloakRealmRoleConverter} plus the
 * {@link SecurityConfig#jwtAuthenticationConverter()} that uses it. They're plain functions from
 * {@link Jwt} to authorities, so no Spring context, no Keycloak and no HTTP are needed. The tokens
 * below are built by hand with only the claims that matter.
 */
class KeycloakRealmRoleConverterUnitTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void convert_mapsEachRealmRoleToARolePrefixedAuthority() {
        Jwt jwt = token(Map.of("realm_access", Map.of("roles", List.of("STAFF", "CUSTOMER"))));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STAFF", "ROLE_CUSTOMER");
    }

    @Test
    void convert_withoutRealmAccessClaim_returnsNoAuthorities() {
        Jwt jwt = token(Map.of("scope", "openid email"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_withRealmAccessButNoRoles_returnsNoAuthorities() {
        Jwt jwt = token(Map.of("realm_access", Map.of()));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_withMalformedRealmAccess_returnsNoAuthoritiesInsteadOfThrowing() {
        // Wrong shapes must not become a 500: an empty authority list just fails authorization.
        assertThat(converter.convert(token(Map.of("realm_access", "STAFF")))).isEmpty();
        assertThat(converter.convert(token(Map.of("realm_access", Map.of("roles", "STAFF"))))).isEmpty();
    }

    @Test
    void convert_skipsNonStringRoleEntries() {
        Jwt jwt = token(Map.of("realm_access", Map.of("roles", List.of("STAFF", 42))));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_STAFF");
    }

    @Test
    void jwtAuthenticationConverter_usesEmailClaimAsNameAndRealmRolesAsAuthorities() {
        Jwt jwt = token(Map.of(
                "email", "alice@example.com",
                "realm_access", Map.of("roles", List.of("CUSTOMER"))));

        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) SecurityConfig.jwtAuthenticationConverter().convert(jwt);

        // The ownership rules compare authentication.name with Order.customerEmail, so the name
        // must be the email and not Keycloak's opaque subject id.
        assertThat(authentication.getName()).isEqualTo("alice@example.com");
        // FACTOR_BEARER is added by Spring Security 7 itself. It records *how* the user
        // authenticated (a bearer token), the building block for its multi-factor support.
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "FACTOR_BEARER");
    }

    private static Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("f3b1c2d4-keycloak-user-id")
                .claims(all -> all.putAll(claims))
                .build();
    }
}
