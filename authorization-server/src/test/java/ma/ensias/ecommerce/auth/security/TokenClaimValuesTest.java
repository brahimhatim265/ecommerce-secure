package ma.ensias.ecommerce.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenClaimValuesTest {

    @Test
    void nameJoinsFirstAndLastNameWithOneSpace() {
        assertThat(TokenClaimValues.fullName("Hamza", "Belhaj")).contains("Hamza Belhaj");
    }

    @Test
    void nameRemovesSuperfluousWhitespace() {
        assertThat(TokenClaimValues.fullName("  Hamza  ", "\tBel   haj \n")).contains("Hamza Bel haj");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "Hamza, NULL, Hamza",
            "Hamza, '', Hamza",
            "Hamza, '   ', Hamza",
            "NULL, Belhaj, Belhaj",
            "'', Belhaj, Belhaj",
            "'  ', Belhaj, Belhaj"
    }, nullValues = "NULL")
    void nameUsesTheOtherPartWhenOneIsMissing(String firstName, String lastName, String expected) {
        assertThat(TokenClaimValues.fullName(firstName, lastName)).contains(expected);
    }

    @Test
    void nameIsEmptyWhenBothPartsAreBlank() {
        assertThat(TokenClaimValues.fullName(null, null)).isEmpty();
        assertThat(TokenClaimValues.fullName(" ", "")).isEmpty();
    }

    @Test
    void rolesAreExportedWithoutPrefix() {
        assertThat(TokenClaimValues.roles(authorities("ROLE_CUSTOMER"))).containsExactly("CUSTOMER");
    }

    @Test
    void rolesAreSortedAndWithoutDuplicates() {
        assertThat(TokenClaimValues.roles(authorities("ROLE_CUSTOMER", "ROLE_ADMIN", "ROLE_CUSTOMER")))
                .containsExactly("ADMIN", "CUSTOMER");
    }

    @Test
    void authoritiesWithoutRolePrefixAreNotExported() {
        assertThat(TokenClaimValues.roles(authorities("SCOPE_openid", "ADMIN", "ROLE_CUSTOMER", "role_admin")))
                .containsExactly("CUSTOMER");
    }

    @Test
    void bareRolePrefixIsIgnored() {
        assertThat(TokenClaimValues.roles(authorities("ROLE_", "ROLE_  "))).isEmpty();
    }

    @Test
    void noAuthorityGivesAnEmptyList() {
        assertThat(TokenClaimValues.roles(List.of())).isEmpty();
    }

    private static List<GrantedAuthority> authorities(String... values) {
        return Arrays.stream(values)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}