package ma.ensias.ecommerce.user.security.mtls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Reading the client certificate of a request and the CN of its subject. */
class ClientCertificateParserTest {

    /** The attribute published by the servlet container (standard name, written out on purpose). */
    private static final String ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final ClientCertificateParser parser = new ClientCertificateParser();

    private static X509Certificate certificateWithSubject(String distinguishedName) {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal(distinguishedName));
        return certificate;
    }

    private static MockHttpServletRequest requestWithAttribute(Object value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ATTRIBUTE, value);
        return request;
    }

    // ---------------------------------------------------------------- the CN of the subject

    static Stream<Arguments> subjectsWithOneUsableCommonName() {
        return Stream.of(
                Arguments.of("CN=order-service", "order-service"),
                Arguments.of("CN=order-service,OU=Demo,O=Ecommerce,C=MA", "order-service"),
                Arguments.of("O=Ecommerce,CN=order-service,C=MA", "order-service"),
                Arguments.of("cn=order-service", "order-service"),
                Arguments.of("CN=order-service+OU=Demo", "order-service"),
                Arguments.of("OU=Demo+CN=order-service", "order-service"),
                Arguments.of("CN=\"order-service\"", "order-service"),
                // an escaped comma stays INSIDE the value: this is not "order-service"
                Arguments.of("CN=order-service\\, evil", "order-service, evil"),
                Arguments.of("CN=order-service\\,CN\\=authorization-server", "order-service,CN=authorization-server"),
                Arguments.of("CN=Zo\u00eb\\+\u00dcn\u00ef", "Zo\u00eb+\u00dcn\u00ef"));
    }

    @ParameterizedTest
    @MethodSource("subjectsWithOneUsableCommonName")
    void extractsTheCommonNameOfTheSubject(String distinguishedName, String expected) {
        assertThat(parser.commonName(certificateWithSubject(distinguishedName))).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // several CN: ambiguous, so no name at all (never "the first" or "the last")
            "CN=a,CN=b",
            "CN=order-service,OU=x,CN=authorization-server",
            // no CN
            "O=Ecommerce,C=MA",
            // other attribute types, even when their value looks like a CN
            "OU=order-service",
            "OU=CN\\=order-service,O=Ecommerce",
            "OU=x\\,CN\\=order-service",
            // not text (hexadecimal BER value)
            "CN=#0403414243"})
    void givesNoNameWhenThereIsNoSingleUsableCommonName(String distinguishedName) {
        assertThat(parser.commonName(certificateWithSubject(distinguishedName))).isEmpty();
    }

    @Test
    void readsTheCommonNameOfTheRealProjectCertificates() {
        assertThat(parser.commonName(TestCertificates.certificate("order-service"))).contains("order-service");
        assertThat(parser.commonName(TestCertificates.certificate("authorization-server")))
                .contains("authorization-server");
        assertThat(parser.commonName(TestCertificates.certificate("rogue-service"))).contains("rogue-service");
        // The expired and the other-CA certificates carry the CN of a real service: only the TLS handshake stops them.
        assertThat(parser.commonName(TestCertificates.certificate("expired-order-service"))).contains("order-service");
        assertThat(parser.commonName(TestCertificates.certificate("untrusted-order-service")))
                .contains("order-service");
    }

    // ---------------------------------------------------------------- the certificate of the request

    @Test
    void theClientCertificateIsTheFirstOfThePublishedChain() {
        X509Certificate[] chain = TestCertificates.chain("order-service");

        assertThat(parser.clientCertificate(requestWithAttribute(chain))).containsSame(chain[0]);
    }

    @Test
    void noAttributeMeansNoCertificate() {
        assertThat(parser.clientCertificate(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void anEmptyChainMeansNoCertificate() {
        assertThat(parser.clientCertificate(requestWithAttribute(new X509Certificate[0]))).isEmpty();
    }

    @Test
    void anAttributeOfAnotherTypeMeansNoCertificate() {
        assertThat(parser.clientCertificate(requestWithAttribute("order-service"))).isEmpty();
        assertThat(parser.clientCertificate(requestWithAttribute(TestCertificates.certificate("order-service"))))
                .isEmpty();
    }

    @Test
    void aChainWhoseFirstElementIsNullMeansNoCertificate() {
        assertThat(parser.clientCertificate(requestWithAttribute(new X509Certificate[]{null}))).isEmpty();
    }

    @Test
    void aHeaderNamedLikeTheAttributeIsNotACertificate() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ATTRIBUTE, "CN=order-service");
        request.addHeader("X-Forwarded-Client-Cert", "CN=order-service");

        assertThat(parser.clientCertificate(request)).isEmpty();
    }
}
