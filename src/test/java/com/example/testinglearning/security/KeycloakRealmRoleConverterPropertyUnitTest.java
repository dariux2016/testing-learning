package com.example.testinglearning.security;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests (jqwik) for {@link KeycloakRealmRoleConverter} (see
 * docs/testing-strategy.md §9).
 *
 * <p>The converter reads a claim from a token we don't control, so the interesting question is
 * "can <em>any</em> shape of {@code realm_access} make it throw?" Hand-written examples
 * ({@link KeycloakRealmRoleConverterUnitTest}) cover the shapes we thought of. The generator below
 * produces the ones we didn't: numbers, booleans, mixed lists, maps with and without
 * {@code roles}, roles that are themselves maps.
 */
class KeycloakRealmRoleConverterPropertyUnitTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Property
    void convert_neverThrows_andOnlyEverEmitsRoleAuthorities(@ForAll("anyRealmAccessValue") Object realmAccess) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", realmAccess)
                .build();

        assertThat(converter.convert(jwt))
                .allSatisfy(authority -> assertThat(authority.getAuthority()).startsWith("ROLE_"));
    }

    @Property
    void convert_mapsEveryStringRoleOneToOneAndInOrder(
            @ForAll List<@AlphaChars @StringLength(min = 1, max = 20) String> roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", roles))
                .build();

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyElementsOf(roles.stream().map(role -> "ROLE_" + role).toList());
    }

    /** Arbitrary JSON-like values: scalars, lists of scalars, and maps, with or without "roles". */
    @Provide
    Arbitrary<Object> anyRealmAccessValue() {
        Arbitrary<Object> scalar = Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(10).map(value -> (Object) value),
                Arbitraries.integers().map(value -> (Object) value),
                Arbitraries.of(true, false).map(value -> (Object) value));
        Arbitrary<Object> listOfScalars = scalar.list().ofMaxSize(5).map(value -> (Object) value);
        Arbitrary<Object> mapWithRoles = Arbitraries.oneOf(scalar, listOfScalars, Arbitraries.maps(
                        Arbitraries.strings().alpha().ofLength(3), scalar).ofMaxSize(3).map(value -> (Object) value))
                .map(roles -> (Object) Map.of("roles", roles));
        Arbitrary<Object> mapWithoutRoles = Arbitraries.maps(Arbitraries.strings().alpha().ofLength(4), scalar)
                .ofMaxSize(3).map(value -> (Object) value);
        return Arbitraries.oneOf(scalar, listOfScalars, mapWithRoles, mapWithoutRoles);
    }
}
