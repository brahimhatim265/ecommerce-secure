package ma.ensias.ecommerce.payment.security.mtls;

import ma.ensias.ecommerce.payment.security.TestJwtDecoderConfig;
import ma.ensias.ecommerce.payment.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A REAL TLS connection to the REAL server (random port, real keystores of the module). The clients trust only the
 * project CA and verify the host name, exactly like the services do. The JWT travels in the Authorization header, as
 * order-service relays it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:payments-mtls-${random.uuid};DB_CLOSE_DELAY=-1")
@Import(TestJwtDecoderConfig.class)
class ServiceMtlsIntegrationTest {

    private static final String BODY = "{\"orderId\":9001,\"ownerId\":\"" + TestJwtSupport.SUBJECT + "\","
            + "\"amount\":6780.00,\"currency\":\"MAD\",\"method\":\"CARD\",\"cardToken\":\"tok_visa_approved\"}";

    private static final AtomicInteger KEYS = new AtomicInteger();

    @LocalServerPort
    private int port;

    private HttpResponse<String> getHealth(HttpClient client) throws IOException, InterruptedException {
        return send(client, HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/actuator/health")).GET());
    }

    /** POST /payments with a fresh idempotency key, with or without the user's JWT. */
    private HttpResponse<String> pay(HttpClient client, boolean withToken) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "tls-it-" + KEYS.incrementAndGet())
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
        if (withToken) {
            request.header("Authorization", "Bearer " + TestJwtSupport.validToken());
        }
        return send(client, request);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------- accepted

    @Test
    void orderServiceWithItsCertificateAndTheRelayedJwtIsAccepted() throws Exception {
        HttpResponse<String> response = pay(TestTlsClients.holding("order-service"), true);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("SUCCEEDED");
    }

    // ---------------------------------------------------------------- 401

    @Test
    void orderServiceWithItsCertificateButWithoutJwtGets401() throws Exception {
        assertThat(pay(TestTlsClients.holding("order-service"), false).statusCode()).isEqualTo(401);
    }

    @Test
    void withoutCertificateEvenWithAValidJwtGets401() throws Exception {
        assertThat(pay(TestTlsClients.withoutCertificate(), true).statusCode()).isEqualTo(401);
    }

    @Test
    void withoutCertificateThePublicEndpointAnswers200() throws Exception {
        assertThat(getHealth(TestTlsClients.withoutCertificate()).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- 403

    @Test
    void aCertificateSignedByTheProjectCaButWithAnUnknownCnGets403() throws Exception {
        assertThat(pay(TestTlsClients.holding("rogue-service"), true).statusCode()).isEqualTo(403);
    }

    @Test
    void theCertificateOfAnotherProjectServiceGets403() throws Exception {
        assertThat(pay(TestTlsClients.holding("authorization-server"), true).statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- invalid certificate: no HTTP answer at all

    @Test
    void anExpiredCertificateFailsTheHandshake() {
        assertThatThrownBy(() -> pay(TestTlsClients.holding("expired-order-service"), true))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aCertificateFromAnotherCaFailsTheHandshake() {
        // The client insists on presenting it: a JDK client would otherwise present nothing and be an anonymous caller.
        assertThatThrownBy(() -> pay(TestTlsClients.insistingOnPresenting("untrusted-order-service"), true))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- no clear text

    @Test
    void thereIsNoHttpListener() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException connectionFailure) {
            return; // no clear-text exchange is possible at all
        }
        // Tomcat may answer a clear-text request on its TLS port with a bare 400: it never reaches the application.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("\"status\":\"UP\"");
    }
}
