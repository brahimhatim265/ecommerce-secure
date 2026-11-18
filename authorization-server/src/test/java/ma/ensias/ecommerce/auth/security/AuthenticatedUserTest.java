package ma.ensias.ecommerce.auth.security;

import ma.ensias.ecommerce.auth.dto.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AuthenticatedUserTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String EMAIL = "hamza.belhaj@example.com";

    private static AuthenticatedUser userWithRoles(Set<String> roles) {
        return AuthenticatedUser.from(new VerifiedIdentity(ID, EMAIL, "Hamza", "Belhaj", roles));
    }

    @Test
    void copiesTheIdentityFields() {
        AuthenticatedUser user = userWithRoles(Set.of("CUSTOMER"));

        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.firstName()).isEqualTo("Hamza");
        assertThat(user.lastName()).isEqualTo("Belhaj");
    }

    @Test
    void usernameIsTheUuidAsText() {
        assertThat(userWithRoles(Set.of("CUSTOMER")).getUsername())
                .isEqualTo("00000000-0000-4000-8000-000000000001");
    }

    @Test
    void rolesBecomeRolePrefixedAuthorities() {
        List<String> authorities = userWithRoles(Set.of("CUSTOMER", "ADMIN")).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).containsExactly("ROLE_ADMIN", "ROLE_CUSTOMER");
    }

    @Test
    void holdsNoPassword() {
        assertThat(userWithRoles(Set.of("CUSTOMER")).getPassword()).isNull();
    }

    @Test
    void isActiveAndNotExpiredOrLocked() {
        AuthenticatedUser user = userWithRoles(Set.of("CUSTOMER"));

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void toStringExposesNeitherEmailNorName() {
        String text = userWithRoles(Set.of("CUSTOMER")).toString();

        assertThat(text).contains(ID.toString()).contains("CUSTOMER");
        assertThat(text).doesNotContain(EMAIL).doesNotContain("Hamza").doesNotContain("Belhaj");
    }

    @Test
    void rejectsAMissingId() {
        assertThatNullPointerException().isThrownBy(
                () -> new AuthenticatedUser(null, EMAIL, "Hamza", "Belhaj", Set.of("CUSTOMER")));
    }
}