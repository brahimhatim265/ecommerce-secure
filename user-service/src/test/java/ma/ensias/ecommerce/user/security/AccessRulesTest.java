package ma.ensias.ecommerce.user.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessRulesTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000008");

    private final AccessRules access = new AccessRules();
    private final JwtAuthenticationConverter converter = new JwtRolesConfig().jwtAuthenticationConverter();

    private Authentication token(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token-value-for-tests")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of(roles))
                .build();
        return converter.convert(jwt);
    }

    private Authentication customer() {
        return token(CUSTOMER_ID.toString(), "CUSTOMER");
    }

    private Authentication admin() {
        return token(ADMIN_ID.toString(), "ADMIN");
    }

    @Test
    void theSubjectOfTheTokenIsTheIdentityOfTheUser() {
        assertThat(access.subjectOf(customer())).contains(CUSTOMER_ID);
    }

    @Test
    void ownerOwnsTheirResource() {
        assertThat(access.isOwner(customer(), CUSTOMER_ID)).isTrue();
        assertThat(access.isOwnerOrAdmin(customer(), CUSTOMER_ID)).isTrue();
    }

    @Test
    void anotherCustomerDoesNotOwnTheResource() {
        assertThat(access.isOwner(customer(), OTHER_ID)).isFalse();
        assertThat(access.isOwnerOrAdmin(customer(), OTHER_ID)).isFalse();
    }

    @Test
    void customerIsNotAdmin() {
        assertThat(access.isAdmin(customer())).isFalse();
    }

    @Test
    void adminIsAdminAndPassesOwnerOrAdminOnAnyResource() {
        assertThat(access.isAdmin(admin())).isTrue();
        assertThat(access.isOwnerOrAdmin(admin(), CUSTOMER_ID)).isTrue();
        assertThat(access.isOwnerOrAdmin(admin(), OTHER_ID)).isTrue();
    }

    @Test
    void adminIsNotTheOwnerOfEverything() {
        assertThat(access.isOwner(admin(), CUSTOMER_ID)).isFalse();
    }

    @Test
    void aMissingOwnerIsNeverGrantedToACustomer() {
        assertThat(access.isOwner(customer(), null)).isFalse();
        assertThat(access.isOwnerOrAdmin(customer(), null)).isFalse();
    }

    @Test
    void noAuthenticationGivesNothing() {
        assertThat(access.subjectOf(null)).isEmpty();
        assertThat(access.isAdmin(null)).isFalse();
        assertThat(access.isOwner(null, CUSTOMER_ID)).isFalse();
        assertThat(access.isOwnerOrAdmin(null, CUSTOMER_ID)).isFalse();
    }

    @Test
    void anAnonymousCallerGivesNothing() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(access.subjectOf(anonymous)).isEmpty();
        assertThat(access.isAdmin(anonymous)).isFalse();
        assertThat(access.isOwnerOrAdmin(anonymous, CUSTOMER_ID)).isFalse();
    }

    @Test
    void aSubjectThatIsNotAUuidGivesNoIdentity() {
        Authentication notAUuid = token("not-a-uuid", "CUSTOMER");
        assertThat(access.subjectOf(notAUuid)).isEmpty();
        assertThat(access.isOwner(notAUuid, CUSTOMER_ID)).isFalse();
    }

    @Test
    void anAuthenticationThatIsNotAJwtGivesNoIdentity() {
        Authentication notAJwt = UsernamePasswordAuthenticationToken.authenticated(CUSTOMER_ID.toString(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        assertThat(access.subjectOf(notAJwt)).isEmpty();
        assertThat(access.isOwner(notAJwt, CUSTOMER_ID)).isFalse();
    }
}
