package ma.ensias.ecommerce.user.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class RolesClaimValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error MISSING_ROLES =
            new OAuth2Error("invalid_token", "The token has no roles claim", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return jwt.hasClaim("roles")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(MISSING_ROLES);
    }
}