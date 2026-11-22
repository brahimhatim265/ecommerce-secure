package ma.ensias.ecommerce.auth.client;

import ma.ensias.ecommerce.auth.config.RestClientConfig;
import ma.ensias.ecommerce.auth.dto.VerifiedIdentity;
import ma.ensias.ecommerce.auth.client.TestHttpsServer.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REAL client of authorization-server (bean of RestClientConfig, real "mtls" SSL bundle) talks to a small HTTPS
 * server that REQUIRES a client certificate: the call must succeed and the server must see the certificate of
 * authorization-server. A server that is not signed by the project CA must be refused, and must never receive a password.
 */
@SpringBootTest
class UserServiceClientMtlsTest {

    private static final String TRUSTSTORE = "certs/truststore.p12";
    private static final String EMAIL = "hamza.belhaj@example.com";
    private static final String PASSWORD = "Demo@2026";
    private static final String IDENTITY_JSON = "{\"id\":\"00000000-0000-4000-8000-000000000001\","
            + "\"email\":\"hamza.belhaj@example.com\",\"firstName\":\"Hamza\",\"lastName\":\"Belhaj\","
            + "\"roles\":[\"CUSTOMER\"]}";

    // Started before the Spring context, because the base URL of the client depends on its port.
    private static final TestHttpsServer USER_SERVICE =
            TestHttpsServer.startRequiringClientCertificate("certs/user-service.p12", TRUSTSTORE);

    static {
        USER_SERVICE.handleJson("/internal/auth/verify", IDENTITY_JSON);
    }

    @DynamicPropertySource
    static void userServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("services.user.base-url", USER_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        USER_SERVICE.close();
    }

    @Autowired
    private UserServiceClient userServiceClient;

    @Autowired
    private SslBundles sslBundles;

    @Test
    void presentsTheCertificateOfAuthorizationServerToUserService() {
        VerifiedIdentity identity = userServiceClient.verify(EMAIL, PASSWORD);

        assertThat(identity.email()).isEqualTo(EMAIL);
        RecordedRequest request = USER_SERVICE.lastRequest();
        assertThat(request.clientCommonName()).isEqualTo("authorization-server");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/internal/auth/verify");
    }

    @Test
    void refusesAServerWhoseCertificateIsNotSignedByTheProjectCaAndNeverSendsItThePassword() {
        try (TestHttpsServer untrusted = TestHttpsServer.start("certs/untrusted-order-service.p12", 0)) {
            untrusted.handleJson("/internal/auth/verify", IDENTITY_JSON);
            RestClient client = new RestClientConfig()
                    .userServiceRestClient(RestClient.builder(), sslBundles, untrusted.baseUrl());

            assertThatThrownBy(() -> new UserServiceClient(client).verify(EMAIL, PASSWORD))
                    .isInstanceOf(AuthenticationServiceException.class)
                    .hasMessageContaining("unreachable");

            // The handshake failed: the request, which carries the password, never reached that server.
            assertThat(untrusted.requests()).isEmpty();
        }
    }
}
