package ma.ensias.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.dto.PaymentRequest;
import ma.ensias.ecommerce.order.dto.PaymentResult;
import ma.ensias.ecommerce.order.dto.RemoteApiError;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Calls payment-service. When no HTTP response is received (I/O error or timeout) the call is retried ONCE with
 * the same Idempotency-Key, which payment-service makes safe. No retry on a received HTTP response.
 */
@Component
public class PaymentClient {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);
    private static final String REJECTED_MESSAGE = "Payment request rejected by payment-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a payment (201) or replays it (200).
     *
     * @throws InvalidRequestException  if payment-service answers 400 (the original message is kept)
     * @throws ExternalServiceException on timeout, unreachable service, other 4xx or 5xx, unreadable answer
     */
    public PaymentResult createPayment(PaymentRequest request, String idempotencyKey) {
        try {
            return postWithSingleRetry(request, idempotencyKey);
        } catch (HttpClientErrorException.BadRequest e) {
            RemoteApiError error = parseError(e);
            throw new InvalidRequestException(
                    error != null && error.message() != null ? error.message() : REJECTED_MESSAGE,
                    error != null && error.details() != null ? error.details() : List.of());
        } catch (RestClientResponseException e) {
            log.warn("payment-service answered HTTP {}", e.getStatusCode().value());
            throw new ExternalServiceException("payment-service returned an error (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            log.warn("payment-service unreachable or timed out: {}", e.getMessage());
            throw new ExternalServiceException("payment-service is unreachable or timed out");
        } catch (RestClientException e) {
            log.warn("payment-service response could not be read: {}", e.getMessage());
            throw new ExternalServiceException("payment-service returned an unreadable response");
        }
    }

    private PaymentResult postWithSingleRetry(PaymentRequest request, String idempotencyKey) {
        try {
            return post(request, idempotencyKey);
        } catch (ResourceAccessException firstFailure) {
            log.warn("No response from payment-service ({}), retrying once with the same Idempotency-Key {}",
                    firstFailure.getMessage(), idempotencyKey);
            return post(request, idempotencyKey);
        }
    }

    private PaymentResult post(PaymentRequest request, String idempotencyKey) {
        PaymentResult result = restClient.post()
                .uri("/payments")
                .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PaymentResult.class);
        if (result == null) {
            throw new ExternalServiceException("payment-service returned an empty response");
        }
        return result;
    }

    private RemoteApiError parseError(RestClientResponseException e) {
        try {
            return objectMapper.readValue(e.getResponseBodyAsString(), RemoteApiError.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
