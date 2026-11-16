package ma.ensias.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.dto.PaymentRequest;
import ma.ensias.ecommerce.order.dto.PaymentResult;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentClientTest {

    private static final String URL = "http://payment-service.test/payments";
    private static final String OK_BODY = "{\"id\":11,\"reference\":\"PAY-ABC\",\"status\":\"SUCCEEDED\","
            + "\"unexpectedField\":true}";

    private MockRestServiceServer server;
    private PaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment-service.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PaymentClient(builder.build(), new ObjectMapper());
    }

    private static PaymentRequest request() {
        return new PaymentRequest(6L, UUID.randomUUID(), new BigDecimal("6780.00"), "MAD", PaymentMethod.CARD,
                "tok_visa_approved");
    }

    @Test
    void successfulCallSendsTheIdempotencyKeyAndToleratesUnknownResponseFields() {
        server.expect(requestTo(URL)).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order-6"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(OK_BODY));

        PaymentResult result = client.createPayment(request(), "order-6");

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        server.verify();
    }

    @Test
    void replayAnswer200IsAlsoAccepted() {
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.createPayment(request(), "order-6").status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void ioErrorIsRetriedOnceWithTheSameKey() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andExpect(header("Idempotency-Key", "order-6"))
                .andRespond(withException(new IOException("connection reset")));
        server.expect(ExpectedCount.once(), requestTo(URL)).andExpect(header("Idempotency-Key", "order-6"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        PaymentResult result = client.createPayment(request(), "order-6");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        server.verify();
    }

    @Test
    void persistentIoErrorGivesUpAfterTheSingleRetry() {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withException(new IOException("down")));

        assertThatThrownBy(() -> client.createPayment(request(), "order-6"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("unreachable");
        server.verify();
    }

    @Test
    void serverErrorIsNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.createPayment(request(), "order-6"))
                .isInstanceOf(ExternalServiceException.class);
        server.verify();
    }

    @Test
    void badRequestIsRelayedWithTheOriginalMessageAndNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":400,\"message\":\"Unknown card token\",\"details\":[]}"));

        assertThatThrownBy(() -> client.createPayment(request(), "order-6"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Unknown card token");
        server.verify();
    }

    @Test
    void badRequestWithAnUnreadableBodyStillGives400() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("not json"));

        assertThatThrownBy(() -> client.createPayment(request(), "order-6"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void otherClientErrorsAreTechnicalFailures() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.createPayment(request(), "order-6"))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void emptyBodyIsATechnicalFailure() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CREATED));

        assertThatThrownBy(() -> client.createPayment(request(), "order-6"))
                .isInstanceOf(ExternalServiceException.class);
    }
}
