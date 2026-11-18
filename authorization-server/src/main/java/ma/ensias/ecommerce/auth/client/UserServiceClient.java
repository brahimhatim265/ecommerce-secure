package ma.ensias.ecommerce.auth.client;

import ma.ensias.ecommerce.auth.dto.VerifiedIdentity;
import ma.ensias.ecommerce.auth.dto.VerifyCredentialsRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls user-service POST /internal/auth/verify and translates the outcome into Spring Security exceptions.
 * Fails closed: anything other than a complete 200 answer is an authentication failure.
 */
@Component
public class UserServiceClient {

    private static final String VERIFY_PATH = "/internal/auth/verify";

    private final RestClient restClient;

    public UserServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @return the verified identity (200)
     * @throws BadCredentialsException       on 401
     * @throws DisabledException             on 403
     * @throws AuthenticationServiceException on any technical problem (timeout, unreachable, 4xx/5xx, bad body)
     */
    public VerifiedIdentity verify(String email, String password) {
        VerifiedIdentity identity;
        try {
            identity = restClient.post()
                    .uri(VERIFY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new VerifyCredentialsRequest(email, password))
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.UNAUTHORIZED), (request, response) -> {
                        throw new BadCredentialsException("Invalid credentials");
                    })
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.FORBIDDEN), (request, response) -> {
                        throw new DisabledException("Account disabled");
                    })
                    .body(VerifiedIdentity.class);
        } catch (AuthenticationException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new AuthenticationServiceException(
                    "user-service answered HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new AuthenticationServiceException("user-service unreachable or timed out", e);
        } catch (RestClientException e) {
            throw new AuthenticationServiceException("user-service response could not be read");
        }

        if (identity == null || identity.id() == null || identity.email() == null || identity.roles() == null) {
            throw new AuthenticationServiceException("user-service returned an incomplete identity");
        }
        return identity;
    }
}