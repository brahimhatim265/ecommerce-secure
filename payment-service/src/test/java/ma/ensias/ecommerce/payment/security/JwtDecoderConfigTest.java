package ma.ensias.ecommerce.payment.security;

import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;

import java.time.Duration;
import java.time.Instant;

import static ma.ensias.ecommerce.payment.security.TestJwtSupport.SUBJECT;
import static ma.ensias.ecommerce.payment.security.TestJwtSupport.TRUSTED_KEY;
import static ma.ensias.ecommerce.payment.security.TestJwtSupport.UNTRUSTED_KEY;
import static ma.ensias.ecommerce.payment.security.TestJwtSupport.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production decoder gets the signing keys over HTTPS, trusting only the project CA (the real "mtls" SSL bundle of
 * application.yml is used), and stays lazy. The fact that this Spring context starts at all, with nothing listening
 * on the default issuer, shows that startup does not need the authorization server.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-jwks-${random.uuid};DB_CLOSE_DELAY=-1")
class JwtDecoderConfigTest {

    /** Certificate of the authorization-server, signed by the project CA. */
    private static final String PROJECT_CA_SERVER = "certs/authorization-server.p12";

    /** A certificate with a valid host name but signed by another CA. */
    private static final String OTHER_CA_SERVER = "certs/untrusted-order-service.p12";

    @Autowired
    private SslBundles sslBundles;

    private TestHttpsServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void validatesATokenWhoseKeysAreFetchedOverHttpsFromTheProjectCa() {
        server = authorizationServer(PROJECT_CA_SERVER, 0);
        String issuer = server.baseUrl();

        assertThat(decoder(issuer).decode(validToken(issuer)).getSubject()).isEqualTo(SUBJECT);
    }

    @Test
    void rejectsATokenSignedWithAKeyThatIsNotInTheJwks() {
        server = authorizationServer(PROJECT_CA_SERVER, 0);
        String issuer = server.baseUrl();
        JwtDecoder decoder = decoder(issuer);
        String token = token(UNTRUSTED_KEY, issuer, inFifteenMinutes(), true);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void refusesAServerWhoseCertificateIsNotSignedByTheProjectCa() {
        server = authorizationServer(OTHER_CA_SERVER, 0);
        String issuer = server.baseUrl();
        JwtDecoder decoder = decoder(issuer);
        String token = validToken(issuer);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtDecoderInitializationException.class)
                .hasStackTraceContaining("javax.net.ssl.SSLHandshakeException");
    }

    @Test
    void isCreatedWithoutTheAuthorizationServerThenFailsCleanlyAndRecoversByItself() {
        server = authorizationServer(PROJECT_CA_SERVER, 0);
        int port = server.port();
        String issuer = server.baseUrl();
        server.close();

        JwtDecoder decoder = decoder(issuer);
        String token = validToken(issuer);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtDecoderInitializationException.class);

        server = authorizationServer(PROJECT_CA_SERVER, port);

        assertThat(decoder.decode(token).getSubject()).isEqualTo(SUBJECT);
    }

    private JwtDecoder decoder(String issuer) {
        return JwtDecoderConfig.lazyDecoder(issuer, sslBundles.getBundle(JwtDecoderConfig.SSL_BUNDLE_NAME));
    }

    /** Minimal authorization server: OIDC metadata and JWKS, served over HTTPS with the given certificate. */
    private static TestHttpsServer authorizationServer(String keystoreResource, int port) {
        TestHttpsServer server = TestHttpsServer.start(keystoreResource, port);
        String issuer = server.baseUrl();
        server.handleJson("/.well-known/openid-configuration",
                "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/oauth2/jwks\"}");
        server.handleJson("/oauth2/jwks", new JWKSet(TRUSTED_KEY.toPublicJWK()).toString());
        return server;
    }

    private static String validToken(String issuer) {
        return token(TRUSTED_KEY, issuer, inFifteenMinutes(), true);
    }

    private static Instant inFifteenMinutes() {
        return Instant.now().plus(Duration.ofMinutes(15));
    }
}
