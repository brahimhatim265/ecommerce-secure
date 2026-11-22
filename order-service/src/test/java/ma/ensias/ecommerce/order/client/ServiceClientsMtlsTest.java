package ma.ensias.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.config.RestClientConfig;
import ma.ensias.ecommerce.order.dto.PaymentRequest;
import ma.ensias.ecommerce.order.dto.PaymentResult;
import ma.ensias.ecommerce.order.dto.UserSummary;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.security.TestHttpsServer;
import ma.ensias.ecommerce.order.security.TestHttpsServer.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REAL clients of order-service (beans of RestClientConfig, real "mtls" SSL bundle) talk to small HTTPS servers
 * that REQUIRE a client certificate: the call must succeed, the server must see the certificate of order-service,
 * and the JWT of the caller must still be relayed. A server that is not signed by the project CA must be refused.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:orders-mtls-${random.uuid};DB_CLOSE_DELAY=-1")
class ServiceClientsMtlsTest {

    private static final String TRUSTSTORE = "certs/truststore.p12";
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String TOKEN = "header.payload.signature";
    private static final String USER_JSON = "{\"id\":\"" + OWNER + "\",\"email\":\"hamza.belhaj@example.com\","
            + "\"roles\":[\"CUSTOMER\"],\"active\":true}";
    private static final String PAYMENT_JSON = "{\"id\":11,\"reference\":\"PAY-ABC\",\"status\":\"SUCCEEDED\"}";

    // Started before the Spring context, because the base URLs of the clients depend on their ports.
    private static final TestHttpsServer USER_SERVICE =
            TestHttpsServer.startRequiringClientCertificate("certs/user-service.p12", TRUSTSTORE);
    private static final TestHttpsServer PAYMENT_SERVICE =
            TestHttpsServer.startRequiringClientCertificate("certs/payment-service.p12", TRUSTSTORE);

    static {
        USER_SERVICE.handleJson("/users", USER_JSON);
        PAYMENT_SERVICE.handleJson("/payments", PAYMENT_JSON);
    }

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("services.user.base-url", USER_SERVICE::baseUrl);
        registry.add("services.payment.base-url", PAYMENT_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopServers() {
        USER_SERVICE.close();
        PAYMENT_SERVICE.close();
    }

    @Autowired
    private UserClient userClient;

    @Autowired
    private PaymentClient paymentClient;

    @Autowired
    private SslBundles sslBundles;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithToken(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue).header("alg", "none").subject("user").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static PaymentRequest paymentRequest() {
        return new PaymentRequest(6L, UUID.randomUUID(), new BigDecimal("6780.00"), "MAD", PaymentMethod.CARD,
                "tok_visa_approved");
    }

    /** A client built like the production ones (same request factory), but aimed at the given server. */
    private RestClient clientTowards(TestHttpsServer server) {
        ClientHttpRequestFactory factory = new RestClientConfig()
                .serviceRequestFactory(sslBundles, Duration.ofSeconds(2), Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(server.baseUrl()).requestFactory(factory).build();
    }

    @Test
    void userClientPresentsTheCertificateOfOrderServiceAndRelaysTheJwt() {
        authenticateWithToken(TOKEN);

        UserSummary user = userClient.getUser(UUID.fromString(OWNER));

        assertThat(user.id()).isEqualTo(UUID.fromString(OWNER));
        assertThat(user.active()).isTrue();
        RecordedRequest request = USER_SERVICE.lastRequest();
        assertThat(request.clientCommonName()).isEqualTo("order-service");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/users/" + OWNER);
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN);
    }

    @Test
    void paymentClientPresentsTheCertificateOfOrderServiceAndRelaysTheJwtAndTheIdempotencyKey() {
        authenticateWithToken(TOKEN);

        PaymentResult result = paymentClient.createPayment(paymentRequest(), "order-6");

        assertThat(result.status()).isEqualTo(PaymentResult.STATUS_SUCCEEDED);
        RecordedRequest request = PAYMENT_SERVICE.lastRequest();
        assertThat(request.clientCommonName()).isEqualTo("order-service");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/payments");
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN)
                .containsEntry("idempotency-key", "order-6");
    }

    @Test
    void refusesAServerWhoseCertificateIsNotSignedByTheProjectCaAndSendsItNothing() {
        try (TestHttpsServer untrusted = TestHttpsServer.start("certs/untrusted-order-service.p12", 0)) {
            untrusted.handleJson("/users", USER_JSON);
            untrusted.handleJson("/payments", PAYMENT_JSON);
            RestClient client = clientTowards(untrusted);

            assertThatThrownBy(() -> new UserClient(client).getUser(UUID.fromString(OWNER)))
                    .isInstanceOf(ExternalServiceException.class)
                    .hasMessageContaining("unreachable");
            assertThatThrownBy(() -> new PaymentClient(client, new ObjectMapper()).createPayment(paymentRequest(), "order-7"))
                    .isInstanceOf(ExternalServiceException.class)
                    .hasMessageContaining("unreachable");

            // The handshake failed: not a single request (so no JWT, no payment data) reached that server.
            assertThat(untrusted.requests()).isEmpty();
        }
    }
}
