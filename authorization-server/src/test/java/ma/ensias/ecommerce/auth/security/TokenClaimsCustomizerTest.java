package ma.ensias.ecommerce.auth.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenClaimsCustomizerTest {

    private static final UUID HAMZA_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);
    private static final Set<String> ALL_SCOPES = Set.of("openid", "profile", "email");

    private final ch.qos.logback.classic.Logger customizerLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TokenClaimsCustomizer.class);
    private final TokenClaimsCustomizer customizer = new TokenClaimsCustomizer();

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        customizerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        customizerLogger.detachAppender(logs);
    }

    @Test
    void accessTokenOfACustomerHasEmailNameAndRoles() {
        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authenticated(hamza()));

        assertThat(claims).containsEntry("sub", HAMZA_ID.toString())
                .containsEntry("email", "hamza.belhaj@example.com")
                .containsEntry("name", "Hamza Belhaj")
                .containsEntry("roles", List.of("CUSTOMER"));
    }

    @Test
    void accessTokenOfAnAdministratorHasTheAdminRole() {
        AuthenticatedUser admin = user("admin@example.com", "Admin", "Root", Set.of("ADMIN"));

        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authenticated(admin));

        assertThat(claims).containsEntry("roles", List.of("ADMIN"));
    }

    @Test
    void severalRolesAreSortedWithoutDuplicateNorPrefix() {
        AuthenticatedUser user = user("a@example.com", "A", "B", Set.of("CUSTOMER", "ADMIN"));

        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authenticated(user));

        assertThat(claims).containsEntry("roles", List.of("ADMIN", "CUSTOMER"));
    }

    @Test
    void authoritiesWithoutTheRolePrefixAreNotExported() {
        AuthenticatedUser user = hamza();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority("SCOPE_openid"),
                        new SimpleGrantedAuthority("FACTOR_PASSWORD")));

        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authentication);

        assertThat(claims).containsEntry("roles", List.of("CUSTOMER"));
    }

    @Test
    void accessTokenWithoutTheEmailScopeHasNoEmail() {
        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN,
                Set.of("openid", "profile"), authenticated(hamza()));

        assertThat(claims).doesNotContainKey("email")
                .containsEntry("name", "Hamza Belhaj")
                .containsEntry("roles", List.of("CUSTOMER"));
    }

    @Test
    void accessTokenWithoutTheProfileScopeHasNoName() {
        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN,
                Set.of("openid", "email"), authenticated(hamza()));

        assertThat(claims).doesNotContainKey("name")
                .containsEntry("email", "hamza.belhaj@example.com")
                .containsEntry("roles", List.of("CUSTOMER"));
    }

    @Test
    void openidAloneGivesRolesButNoEmailNorName() {
        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"), authenticated(hamza()));

        assertThat(claims).doesNotContainKeys("email", "name").containsEntry("roles", List.of("CUSTOMER"));
    }

    @Test
    void idTokenHasEmailAndNameButNoRoles() {
        Map<String, Object> claims = customize(ID_TOKEN, ALL_SCOPES, authenticated(hamza()));

        assertThat(claims).containsEntry("sub", HAMZA_ID.toString())
                .containsEntry("email", "hamza.belhaj@example.com")
                .containsEntry("name", "Hamza Belhaj")
                .doesNotContainKey("roles");
    }

    @Test
    void idTokenRespectsTheScopesToo() {
        Map<String, Object> claims = customize(ID_TOKEN, Set.of("openid"), authenticated(hamza()));

        assertThat(claims).doesNotContainKeys("email", "name", "roles");
    }

    @Test
    void nameIsCleanedWhenOnePartIsBlank() {
        AuthenticatedUser user = user("a@example.com", "  Hamza ", "   ", Set.of("CUSTOMER"));

        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authenticated(user));

        assertThat(claims).containsEntry("name", "Hamza");
    }

    @Test
    void noNameClaimWhenBothPartsAreMissing() {
        AuthenticatedUser user = user("a@example.com", null, " ", Set.of("CUSTOMER"));

        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authenticated(user));

        assertThat(claims).doesNotContainKey("name");
    }

    @Test
    void userWithoutRoleGetsAnEmptyRolesArray() {
        AuthenticatedUser user = user("a@example.com", "A", "B", Set.of());

        Map<String, Object> claims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authenticated(user));

        assertThat(claims).containsEntry("roles", List.of());
    }

    @Test
    void unexpectedPrincipalGetsNoInventedClaimAndAnErrorWithoutPersonalData() {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "hamza.belhaj@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        Map<String, Object> accessClaims = customize(OAuth2TokenType.ACCESS_TOKEN, ALL_SCOPES, authentication);
        Map<String, Object> idClaims = customize(ID_TOKEN, ALL_SCOPES, authentication);

        assertThat(accessClaims).doesNotContainKeys("email", "name", "roles");
        assertThat(idClaims).doesNotContainKeys("email", "name", "roles");

        List<ILoggingEvent> errors = logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(2);
        errors.forEach(event -> assertThat(event.getFormattedMessage())
                .contains("unexpected principal type")
                .doesNotContain("hamza").doesNotContain("example.com").doesNotContain("ADMIN"));
    }

    @Test
    void otherTokenTypesAreLeftUntouched() {
        Map<String, Object> claims = customize(new OAuth2TokenType("refresh_token"), ALL_SCOPES, authenticated(hamza()));

        assertThat(claims).containsOnlyKeys("sub");
    }

    private Map<String, Object> customize(OAuth2TokenType tokenType, Set<String> scopes, Authentication principal) {
        RegisteredClient client = RegisteredClient.withId("client-id")
                .clientId("ecommerce-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:3000/callback")
                .build();
        JwtEncodingContext context = JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject(principal.getName()))
                .registeredClient(client)
                .principal(principal)
                .authorizedScopes(scopes)
                .tokenType(tokenType)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .build();

        customizer.customize(context);

        return context.getClaims().build().getClaims();
    }

    private static Authentication authenticated(AuthenticatedUser user) {
        List<? extends GrantedAuthority> authorities = List.copyOf(user.getAuthorities());
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }

    private static AuthenticatedUser hamza() {
        return new AuthenticatedUser(HAMZA_ID, "hamza.belhaj@example.com", "Hamza", "Belhaj", Set.of("CUSTOMER"));
    }

    private static AuthenticatedUser user(String email, String firstName, String lastName, Set<String> roles) {
        return new AuthenticatedUser(UUID.randomUUID(), email, firstName, lastName, roles);
    }
}