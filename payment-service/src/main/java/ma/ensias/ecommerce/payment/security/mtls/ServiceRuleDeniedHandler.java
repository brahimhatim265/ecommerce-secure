package ma.ensias.ecommerce.payment.security.mtls;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;


public final class ServiceRuleDeniedHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint unauthorized;
    private final AccessDeniedHandler forbidden;

    /** @param unauthorized the existing 401 handler, @param forbidden the existing 403 handler */
    public ServiceRuleDeniedHandler(AuthenticationEntryPoint unauthorized, AccessDeniedHandler forbidden) {
        this.unauthorized = unauthorized;
        this.forbidden = forbidden;
    }

    /** Anonymous caller: Spring Security would answer 401 whatever the reason. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (refusedWith(request, HttpStatus.FORBIDDEN)) {
            forbidden.handle(request, response, new AccessDeniedException("The calling service is not allowed"));
        } else {
            unauthorized.commence(request, response, authException);
        }
    }

    /** Caller with a token: Spring Security would answer 403 whatever the reason. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (refusedWith(request, HttpStatus.UNAUTHORIZED)) {
            unauthorized.commence(request, response,
                    new InsufficientAuthenticationException("A client certificate is required"));
        } else {
            forbidden.handle(request, response, accessDeniedException);
        }
    }

    private static boolean refusedWith(HttpServletRequest request, HttpStatus status) {
        return DenialReason.of(request).filter(reason -> reason.status() == status).isPresent();
    }
}
