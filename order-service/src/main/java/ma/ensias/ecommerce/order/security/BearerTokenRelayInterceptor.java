package ma.ensias.ecommerce.order.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

/**
 * Forwards the JWT of the request being served to every outgoing call, so that user-service and payment-service
 * can validate it themselves.
 * <p>
 * Fails closed: without a JWT in the security context the call is NOT sent (never an anonymous call).
 * The token is never logged, and never written in an exception message.
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new IllegalStateException(
                    "No JWT in the security context: the outgoing call to " + request.getURI().getHost()
                            + " is not sent");
        }
        request.getHeaders().setBearerAuth(jwtAuthentication.getToken().getTokenValue());
        return execution.execute(request, body);
    }
}
