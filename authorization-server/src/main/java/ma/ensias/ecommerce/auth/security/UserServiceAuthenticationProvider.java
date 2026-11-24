package ma.ensias.ecommerce.auth.security;

import ma.ensias.ecommerce.auth.client.UserServiceClient;
import ma.ensias.ecommerce.auth.dto.VerifiedIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.DisabledException;


@Component
public class UserServiceAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(UserServiceAuthenticationProvider.class);
    private static final String LOGIN_FAILED = "Login failed";

    private final UserServiceClient userServiceClient;

    public UserServiceAuthenticationProvider(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        // The login form field is named "username" (Spring Security), but the user types their email in it.
        String email = authentication.getName();
        try {
            return verifyAndBuildToken(authentication, email);
        } catch (AuthenticationException e) {
            log.warn("Login failed for {}: {}", EmailMasker.mask(email), e.getMessage());
            throw userFacing(e);
        }
    }

    private static AuthenticationException userFacing(AuthenticationException e) {
        if (e instanceof BadCredentialsException) {
            return e; // its message is already the generic "Invalid credentials"
        }
        if (e instanceof DisabledException) {
            return new DisabledException(LOGIN_FAILED);
        }
        return new AuthenticationServiceException(LOGIN_FAILED);
    }

    private Authentication verifyAndBuildToken(Authentication authentication, String email) {
        if (email == null || email.isBlank() || !(authentication.getCredentials() instanceof String password)
                || password.isBlank()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        VerifiedIdentity identity = userServiceClient.verify(email, password);
        AuthenticatedUser user = AuthenticatedUser.from(identity);

        // Credentials are null on purpose: the password is not kept after a successful authentication.
        UsernamePasswordAuthenticationToken authenticated =
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        authenticated.setDetails(authentication.getDetails());
        return authenticated;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}