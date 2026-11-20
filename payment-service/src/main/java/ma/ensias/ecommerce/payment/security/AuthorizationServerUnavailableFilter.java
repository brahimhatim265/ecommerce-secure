package ma.ensias.ecommerce.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns "the token could not be checked because the authorization server is unreachable" into a clean
 * 503 ApiError. Without it, that failure escapes the filter chain, goes through the container's error dispatch and
 * is reported as a misleading 401 on the path "/error", as if the token itself were wrong.
 * <p>
 * Registered by SecurityConfig inside the security filter chain (it is deliberately not a bean).
 */
public class AuthorizationServerUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerUnavailableFilter.class);

    private final ApiErrorWriter errorWriter;

    public AuthorizationServerUnavailableFilter(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (JwtDecoderInitializationException | AuthenticationServiceException ex) {
            // Only the exception type is logged: never the message, never the token.
            log.warn("Token validation unavailable ({}): the authorization server is probably unreachable",
                    ex.getClass().getSimpleName());
            errorWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Authentication service unavailable");
        }
    }
}
