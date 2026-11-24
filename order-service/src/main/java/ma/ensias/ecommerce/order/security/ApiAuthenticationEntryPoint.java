package ma.ensias.ecommerce.order.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ma.ensias.ecommerce.order.security.audit.AuditLog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiErrorWriter errorWriter;

    public ApiAuthenticationEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearerSent = authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
        AuditLog.deny(request, bearerSent ? AuditLog.INVALID_TOKEN : AuditLog.MISSING_TOKEN);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        errorWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                bearerSent ? "Invalid or expired token" : "Authentication required");
    }
}
