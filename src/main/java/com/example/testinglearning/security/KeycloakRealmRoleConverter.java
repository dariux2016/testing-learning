package com.example.testinglearning.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turns Keycloak's realm roles into Spring Security authorities (see docs/testing-strategy.md §8).
 *
 * <p>Keycloak puts realm roles in a nested claim, {@code "realm_access": {"roles": ["STAFF", ...]}},
 * which Spring's default converter doesn't read (it only looks at {@code scope}/{@code scp}). Each
 * role becomes {@code ROLE_<name>}, which is what {@code hasRole('STAFF')} checks for.
 *
 * <p>A token without the claim, or with a claim of an unexpected shape, gets no authorities rather
 * than an exception. The request then fails authorization with a clean 403 instead of a 500.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        if (!(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
