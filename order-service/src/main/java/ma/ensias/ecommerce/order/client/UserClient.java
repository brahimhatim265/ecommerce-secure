package ma.ensias.ecommerce.order.client;

import ma.ensias.ecommerce.order.dto.UserSummary;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/** Calls user-service and translates HTTP failures into business exceptions. */
@Component
public class UserClient {

    private static final Logger log = LoggerFactory.getLogger(UserClient.class);

    private final RestClient restClient;

    public UserClient(@Qualifier("userRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches a user by id.
     *
     * @throws ResourceNotFoundException if user-service answers 404
     * @throws ExternalServiceException  if user-service is unreachable, too slow or fails
     */
    public UserSummary getUser(UUID id) {
        try {
            UserSummary user = restClient.get().uri("/users/{id}", id).retrieve().body(UserSummary.class);
            if (user == null) {
                throw new ExternalServiceException("user-service returned an empty response");
            }
            return user;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("User not found");
        } catch (RestClientResponseException e) {
            log.warn("user-service answered HTTP {}", e.getStatusCode().value());
            throw new ExternalServiceException("user-service returned an error (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (ResourceAccessException e) {
            log.warn("user-service unreachable or timed out: {}", e.getMessage());
            throw new ExternalServiceException("user-service is unreachable or timed out");
        } catch (RestClientException e) {
            log.warn("user-service response could not be read: {}", e.getMessage());
            throw new ExternalServiceException("user-service returned an unreadable response");
        }
    }
}
