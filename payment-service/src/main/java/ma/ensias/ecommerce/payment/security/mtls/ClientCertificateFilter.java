package ma.ensias.ecommerce.payment.security.mtls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Optional;


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
