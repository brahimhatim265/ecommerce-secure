package ma.ensias.ecommerce.user.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Builds the validator applied to every access token; shared by production and tests. */
public final class TokenValidators {

    private TokenValidators() {
    }

    public static OAuth2TokenValidator<Jwt> forIssuer(String issuerUri) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new RolesClaimValidator());
    }
}