package ma.ensias.ecommerce.user.security.mtls;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A REAL TLS connection to the REAL server (random port, real keystores of the module). The clients trust only the
 * project CA and verify the host name, exactly like the services do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:users-mtls-${random.uuid};DB_CLOSE_DELAY=-1")
class ServiceMtlsIntegrationTest {

    private static final String VERIFY_BODY = "{\"email\":\"hamza.belhaj@example.com\",\"password\":\"Demo@2026\"}";

    @LocalServerPort
    private int port;

    private HttpResponse<String> getHealth(HttpClient client) throws IOException, InterruptedException {
        return send(client, HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/actuator/health")).GET());
    }

    private HttpResponse<String> verify(HttpClient client) throws IOException, InterruptedException {
        return send(client, HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/internal/auth/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(VERIFY_BODY)));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------- accepted

    @Test
    void theCertificateOfTheAuthorizationServerIsAcceptedOnTheReservedEndpoint() throws Exception {
        HttpResponse<String> response = verify(TestTlsClients.holding("authorization-server"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hamza.belhaj@example.com");
    }

    // ---------------------------------------------------------------- no certificate

    @Test
    void withoutCertificateThePublicEndpointAnswers200() throws Exception {
        assertThat(getHealth(TestTlsClients.withoutCertificate()).statusCode()).isEqualTo(200);
    }

    @Test
    void withoutCertificateTheReservedEndpointAnswers401() throws Exception {
        assertThat(verify(TestTlsClients.withoutCertificate()).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- valid certificate, service not allowed

    @Test
    void theCertificateOfAnotherProjectServiceGets403() throws Exception {
        assertThat(verify(TestTlsClients.holding("order-service")).statusCode()).isEqualTo(403);
    }

    @Test
    void aCertificateSignedByTheProjectCaButWithAnUnknownCnGets403() throws Exception {
        assertThat(verify(TestTlsClients.holding("rogue-service")).statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- invalid certificate: no HTTP answer at all

    @Test
    void anExpiredCertificateFailsTheHandshake() {
        assertThatThrownBy(() -> verify(TestTlsClients.holding("expired-order-service"))).isInstanceOf(IOException.class);
    }

    @Test
    void aCertificateFromAnotherCaFailsTheHandshake() {
        // The client insists on presenting it: a JDK client would otherwise present nothing and be an anonymous caller.
        assertThatThrownBy(() -> verify(TestTlsClients.insistingOnPresenting("untrusted-order-service")))
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
