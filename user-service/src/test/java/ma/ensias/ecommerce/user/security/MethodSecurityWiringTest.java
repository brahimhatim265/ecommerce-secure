package ma.ensias.ecommerce.user.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the three pieces work together: the annotations are switched on, the roles of the token reach
 * {@code hasRole}, and the bean "access" can be called from an expression.
 */
@SpringJUnitConfig(classes = {MethodSecurityConfig.class, AccessRules.class, MethodSecurityWiringTest.Probe.class})
class MethodSecurityWiringTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000008");

    private static final JwtAuthenticationConverter CONVERTER = new JwtRolesConfig().jwtAuthenticationConverter();

    @Component
    public static class Probe {

        @PreAuthorize("hasRole('ADMIN')")
        public String adminOnly() {
            return "ok";
        }

        @PreAuthorize("@access.isOwnerOrAdmin(authentication, #ownerId)")
        public String ownerOrAdmin(UUID ownerId) {
            return "ok";
        }
    }

    @Autowired
    private Probe probe;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(UUID subject, String role) {
        Jwt jwt = Jwt.withTokenValue("token-value-for-tests")
                .header("alg", "RS256")
                .subject(subject.toString())
                .claim("roles", List.of(role))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(CONVERTER.convert(jwt));
        SecurityContextHolder.setContext(context);
    }

    @Test
    void anAdministratorPassesAnAdminRule() {
        authenticateAs(ADMIN_ID, "ADMIN");
        assertThat(probe.adminOnly()).isEqualTo("ok");
    }

    @Test
    void aCustomerIsRefusedByAnAdminRule() {
        authenticateAs(CUSTOMER_ID, "CUSTOMER");
        assertThatThrownBy(() -> probe.adminOnly()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theOwnerPassesTheOwnershipRule() {
        authenticateAs(CUSTOMER_ID, "CUSTOMER");
        assertThat(probe.ownerOrAdmin(CUSTOMER_ID)).isEqualTo("ok");
    }

    @Test
    void anotherCustomerIsRefusedByTheOwnershipRule() {
        authenticateAs(CUSTOMER_ID, "CUSTOMER");
        assertThatThrownBy(() -> probe.ownerOrAdmin(OTHER_ID)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdministratorPassesTheOwnershipRuleOnAnyResource() {
        authenticateAs(ADMIN_ID, "ADMIN");
        assertThat(probe.ownerOrAdmin(CUSTOMER_ID)).isEqualTo("ok");
    }

    @Test
    void anAnonymousCallerIsRefused() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(anonymous);
        SecurityContextHolder.setContext(context);
        assertThatThrownBy(() -> probe.ownerOrAdmin(CUSTOMER_ID)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noAuthenticationAtAllIsRefused() {
        assertThatThrownBy(() -> probe.adminOnly()).isInstanceOfAny(AuthenticationException.class,
                AccessDeniedException.class);
    }
}
