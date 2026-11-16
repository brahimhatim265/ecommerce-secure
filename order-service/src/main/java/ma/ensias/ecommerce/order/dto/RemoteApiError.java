package ma.ensias.ecommerce.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Error body of another service, as received by a client. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteApiError(String message, List<String> details) {
}
