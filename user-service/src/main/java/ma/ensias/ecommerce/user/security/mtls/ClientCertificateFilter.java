package ma.ensias.ecommerce.user.security.mtls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * Records, for EVERY request, who the client certificate says the caller is, as a {@link CallerIdentity} stored in the
 * request attribute {@link CallerIdentity#REQUEST_ATTRIBUTE}.
 * <p>
 * It only DESCRIBES the caller: it never rejects a request and never grants anything. The decision belongs to the
 * authorization rules of Spring Security, which read this attribute. It deliberately does not use Spring Security's
 * X.509 support, which keeps a single authentication per request: here the service (certificate) and the user (JWT)
 * must both stay visible.
 * <p>
 * Registered by SecurityConfig inside the security filter chain (it is deliberately not a bean, so that the servlet
 * container does not register it a second time). The attribute is always overwritten, so nothing set earlier survives.
 */
public class ClientCertificateFilter extends OncePerRequestFilter {

    private final ClientCertificateParser parser;

    public ClientCertificateFilter(ClientCertificateParser parser) {
        this.parser = parser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, identify(request));
        chain.doFilter(request, response);
    }

    private CallerIdentity identify(HttpServletRequest request) {
        Optional<X509Certificate> certificate = parser.clientCertificate(request);
        if (certificate.isEmpty()) {
            return CallerIdentity.noCertificate();
        }
        Optional<String> commonName = parser.commonName(certificate.get());
        return commonName.flatMap(ServiceIdentity::fromCommonName)
                .map(CallerIdentity::service)
                .orElseGet(() -> CallerIdentity.unknownService(commonName.orElse(null)));
    }
}
