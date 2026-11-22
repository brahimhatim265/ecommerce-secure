package ma.ensias.ecommerce.user.security.mtls;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The filter describes the caller on every request, and never rejects anything itself. */
class ClientCertificateFilterTest {

    private static final String ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final ClientCertificateFilter filter = new ClientCertificateFilter(new ClientCertificateParser());

    private static MockHttpServletRequest requestPresenting(X509Certificate[] chain) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ATTRIBUTE, chain);
        return request;
    }

    /** Runs the filter and returns the identity it stored; also checks that the request went on. */
    private CallerIdentity identityStoredFor(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("the filter must let the request go on").isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        return (CallerIdentity) request.getAttribute(CallerIdentity.REQUEST_ATTRIBUTE);
    }

    @Test
    void noCertificateGivesNoCertificate() throws Exception {
        CallerIdentity caller = identityStoredFor(new MockHttpServletRequest());

        assertThat(caller.type()).isEqualTo(CallerIdentity.Type.NO_CERTIFICATE);
        assertThat(caller.certificatePresented()).isFalse();
        assertThat(caller.service()).isEmpty();
        assertThat(caller.commonName()).isEmpty();
    }

    @Test
    void theCertificateOfAProjectServiceGivesItsIdentity() throws Exception {
        CallerIdentity caller = identityStoredFor(requestPresenting(TestCertificates.chain("order-service")));

        assertThat(caller.type()).isEqualTo(CallerIdentity.Type.SERVICE);
        assertThat(caller.certificatePresented()).isTrue();
        assertThat(caller.service()).contains(ServiceIdentity.SERVICE_ORDER);
        assertThat(caller.commonName()).contains("order-service");
    }

    @Test
    void theCertificateOfTheAuthorizationServerGivesServiceAuth() throws Exception {
        CallerIdentity caller = identityStoredFor(requestPresenting(TestCertificates.chain("authorization-server")));

        assertThat(caller.service()).contains(ServiceIdentity.SERVICE_AUTH);
    }

    @Test
    void aCertificateSignedByTheCaButWithAnUnknownCnGivesAnUnknownServiceWithoutAnyRight() throws Exception {
        CallerIdentity caller = identityStoredFor(requestPresenting(TestCertificates.chain("rogue-service")));

        assertThat(caller.type()).isEqualTo(CallerIdentity.Type.UNKNOWN_SERVICE);
        assertThat(caller.certificatePresented()).isTrue();
        assertThat(caller.service()).isEmpty();
        assertThat(caller.commonName()).contains("rogue-service");
    }

    @Test
    void aCertificateWithoutASingleUsableCnGivesAnUnknownServiceWithoutAName() throws Exception {
        X509Certificate ambiguous = mock(X509Certificate.class);
        when(ambiguous.getSubjectX500Principal()).thenReturn(new X500Principal("CN=order-service,CN=authorization-server"));

        CallerIdentity caller = identityStoredFor(requestPresenting(new X509Certificate[]{ambiguous}));

        assertThat(caller.type()).isEqualTo(CallerIdentity.Type.UNKNOWN_SERVICE);
        assertThat(caller.service()).isEmpty();
        assertThat(caller.commonName()).isEmpty();
    }

    @Test
    void anAttributeOfTheWrongTypeGivesNoCertificate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ATTRIBUTE, "order-service");

        assertThat(identityStoredFor(request).type()).isEqualTo(CallerIdentity.Type.NO_CERTIFICATE);
    }

    @Test
    void anIdentityAlreadyPresentOnTheRequestNeverSurvives() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, CallerIdentity.service(ServiceIdentity.SERVICE_AUTH));

        CallerIdentity caller = identityStoredFor(request);

        assertThat(caller.type()).isEqualTo(CallerIdentity.Type.NO_CERTIFICATE);
    }
}
