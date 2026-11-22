package ma.ensias.ecommerce.order.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRolesConfigTest {

    private static final String SUBJECT = "00000000-0000-4000-8000-000000000001";

    private final JwtAuthenticationConverter converter = new JwtRolesConfig().jwtAuthenticationConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value-for-tests")
                .header("alg", "RS256")
                .subject(SUBJECT);
        claims.forEach(builder::claim);
        return builder.build();
    }

    private List<String> authoritiesOf(Map<String, Object> claims) {
        return converter.convert(jwt(claims)).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    void customerRoleBecomesRoleCustomer() {
        assertThat(authoritiesOf(Map.of("roles", List.of("CUSTOMER")))).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void adminRoleBecomesRoleAdmin() {
        assertThat(authoritiesOf(Map.of("roles", List.of("ADMIN")))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void everyRoleOfTheTokenIsConverted() {
        assertThat(authoritiesOf(Map.of("roles", List.of("ADMIN", "CUSTOMER"))))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CUSTOMER");
    }

    @Test
    void emptyRolesGiveNoAuthority() {
        assertThat(authoritiesOf(Map.of("roles", List.of()))).isEmpty();
    }

    @Test
    void missingRolesClaimGivesNoAuthority() {
        assertThat(authoritiesOf(Map.of())).isEmpty();
    }

    @Test
    void scopesAreNotTurnedIntoAuthorities() {
        assertThat(authoritiesOf(Map.of("scope", List.of("openid", "profile"), "roles", List.of("CUSTOMER"))))
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void aRoleAlreadyPrefixedDoesNotBecomeAdmin() {
        assertThat(authoritiesOf(Map.of("roles", List.of("ROLE_ADMIN"))))
                .doesNotContain("ROLE_ADMIN")
                .containsExactly("ROLE_ROLE_ADMIN");
    }

    @Test
    void theNameOfTheAuthenticationIsTheSubjectOfTheToken() {
        assertThat(converter.convert(jwt(Map.of("roles", List.of("CUSTOMER")))).getName()).isEqualTo(SUBJECT);
    }
}
