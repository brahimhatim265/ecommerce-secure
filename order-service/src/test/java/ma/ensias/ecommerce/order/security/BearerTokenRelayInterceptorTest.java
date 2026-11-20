package ma.ensias.ecommerce.order.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BearerTokenRelayInterceptorTest {

    private static final String URL = "http://remote-service.test/resource";
    private static final String TOKEN = "header.payload.signature";

    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://remote-service.test")
                .requestInterceptor(new BearerTokenRelayInterceptor());
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithToken(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue).header("alg", "none").subject("user").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void forwardsTheTokenOfTheCurrentRequestUnchanged() {
        authenticateWithToken(TOKEN);
        server.expect(requestTo(URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess());

        client.get().uri("/resource").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void replacesAnAuthorizationHeaderSetByTheCaller() {
        authenticateWithToken(TOKEN);
        server.expect(requestTo(URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess());

        client.get().uri("/resource").header(HttpHeaders.AUTHORIZATION, "Bearer another-token")
                .retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void withoutAnyAuthenticationTheCallIsNotSent() {
        // the mock server has no expectation: any call that reached it would fail the test
        assertThatThrownBy(() -> client.get().uri("/resource").retrieve().toBodilessEntity())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No JWT in the security context");

        server.verify();
    }

    @Test
    void anAuthenticationThatIsNotAJwtIsNotRelayedAndItsSecretStaysOutOfTheMessage() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("user", "secret-credential", List.of()));

        assertThatThrownBy(() -> client.get().uri("/resource").retrieve().toBodilessEntity())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-credential");

        server.verify();
    }
}
