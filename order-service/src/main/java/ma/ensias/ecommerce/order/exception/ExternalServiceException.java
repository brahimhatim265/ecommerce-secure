package ma.ensias.ecommerce.order.exception;

import java.util.List;

/** Raised when a remote service fails, times out or is unreachable (HTTP 503). */
public class ExternalServiceException extends RuntimeException {

    private final List<String> details;

    public ExternalServiceException(String message) {
        this(message, List.of());
    }

    public ExternalServiceException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<String> getDetails() {
        return details;
    }
}
