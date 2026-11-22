package ma.ensias.ecommerce.payment.security.mtls;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * For MockMvc: presents a test certificate the way the servlet container does, through the standard request attribute
 * (there is no TLS connection in MockMvc, so the project CA is not involved here; TLS has its own tests).
 */
public final class ClientCertificate {

    private static final String ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private ClientCertificate() {
    }

    /** The certificate chain of the test keystore certs/NAME.p12 (order-service, authorization-server, rogue-service...). */
    public static RequestPostProcessor of(String keystoreName) {
        return request -> {
            request.setAttribute(ATTRIBUTE, TestCertificates.chain(keystoreName));
            return request;
        };
    }

    public static RequestPostProcessor orderService() {
        return of("order-service");
    }
}
