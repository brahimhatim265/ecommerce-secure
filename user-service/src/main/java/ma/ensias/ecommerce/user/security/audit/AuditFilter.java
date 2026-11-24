package ma.ensias.ecommerce.user.security.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Writes the ALLOW line of the audit trail for every request that went through the security rules and was served.
 * <p>
 * It runs AFTER the Spring Security filter chain (a plain filter bean has the lowest precedence), so when it gets
 * the request the caller has already passed the URL rules; a refusal by a rule or by an error handler has been
 * recorded by {@link AuditLog#deny} and makes {@link AuditLog#allow} a no-op. The health probe is left out: it would
 * fill the trail without telling anything.
 */
@Component
public class AuditFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        AuditLog.allow(request);
    }
}
