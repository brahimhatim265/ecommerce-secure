package ma.ensias.ecommerce.order.integration;

import ma.ensias.ecommerce.order.security.TestJwtDecoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /orders forwards the caller's token, unchanged, to user-service and to payment-service (including the
 * payment retry). The remote services are replaced by a mock HTTP server; the production RestClient beans
 * (and therefore their interceptor) are used, only their request factory is swapped.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders-relay-${random.uuid};DB_CLOSE_DELAY=-1",
        "services.user.base-url=http://user-service.test",
        "services.payment.base-url=http://payment-service.test"})
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class TokenRelayIntegrationTest {

    private static final String TOKEN = "header.payload.signature";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String USER_URL = "http://user-service.test/users/" + OWNER;
    private static final String PAYMENT_URL = "http://payment-service.test/payments";
    private static final String ACTIVE_USER = "{\"id\":\"" + OWNER + "\",\"email\":\"hamza.belhaj@example.com\","
            + "\"roles\":[\"CUSTOMER\"],\"active\":true}";
    private static final String PAYMENT_SUCCEEDED =
            "{\"id\":6,\"reference\":\"PAY-ABC\",\"orderId\":6,\"status\":\"SUCCEEDED\"}";
    private static final String ORDER_BODY = "{\"ownerId\":\"" + OWNER + "\",\"shippingAddress\":\"45 Rue Ibnou Katir\","
            + "\"shippingCity\":\"Casablanca\",\"paymentMethod\":\"CARD\",\"cardToken\":\"tok_visa_approved\","
            + "\"items\":[{\"productId\":1,\"quantity\":1}]}";

    @TestConfiguration
    static class MockHttpConfig {

        private final RestTemplate restTemplate = new RestTemplate();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        @Bean
        @Primary
        ClientHttpRequestFactory mockRequestFactory() {
            return restTemplate.getRequestFactory();
        }

        @Bean
        MockRestServiceServer mockRestServiceServer() {
            return server;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockRestServiceServer server;

    /** A caller whose access token has an exact, known value that the relay must forward untouched. */
    private static RequestPostProcessor callerHoldingTheToken() {
        return jwt().jwt(token -> token.tokenValue(TOKEN).claim("roles", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private void expectUserLookup() {
        server.expect(requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(ACTIVE_USER, MediaType.APPLICATION_JSON));
    }

    @Test
    void orderCreationForwardsTheTokenToUserServiceAndPaymentService() throws Exception {
        expectUserLookup();
        server.expect(requestTo(PAYMENT_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body(PAYMENT_SUCCEEDED));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY)
                        .with(callerHoldingTheToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        server.verify();
    }

    @Test
    void thePaymentRetryForwardsTheTokenToo() throws Exception {
        expectUserLookup();
        server.expect(requestTo(PAYMENT_URL)).andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withException(new IOException("connection reset")));
        server.expect(requestTo(PAYMENT_URL)).andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body(PAYMENT_SUCCEEDED));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY)
                        .with(callerHoldingTheToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        server.verify();
    }
}
