package ma.ensias.ecommerce.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.Set;


@Component
public class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    static final String ROLES_CLAIM = "roles";

    private static final Logger log = LoggerFactory.getLogger(TokenClaimsCustomizer.class);
    private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

    @Override
    public void customize(JwtEncodingContext context) {
        boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
        boolean idToken = ID_TOKEN.equals(context.getTokenType());
        if (!accessToken && !idToken) {
            return;
        }

        Authentication authentication = context.getPrincipal();
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            log.error("Token claims not customized: unexpected principal type {} for token type {}",
                    typeOf(authentication.getPrincipal()), context.getTokenType().getValue());
            return;
        }

        Set<String> scopes = context.getAuthorizedScopes();
        JwtClaimsSet.Builder claims = context.getClaims();

        if (scopes.contains(OidcScopes.EMAIL)) {
            claims.claim(StandardClaimNames.EMAIL, user.email());
        }
        if (scopes.contains(OidcScopes.PROFILE)) {
            TokenClaimValues.fullName(user.firstName(), user.lastName())
                    .ifPresent(name -> claims.claim(StandardClaimNames.NAME, name));
        }
        if (accessToken) {
            claims.claim(ROLES_CLAIM, TokenClaimValues.roles(authentication.getAuthorities()));
        }
    }

    private static String typeOf(Object principal) {
        return principal == null ? "null" : principal.getClass().getName();
    }
}