package ma.ensias.ecommerce.order.exception;

import java.util.List;

/** Raised when a request is refused as invalid, locally or by a remote service (HTTP 400). */
public class InvalidRequestException extends RuntimeException {

    private final List<String> details;

    public InvalidRequestException(String message) {
        this(message, List.of());
    }

    public InvalidRequestException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<String> getDetails() {
        return details;
    }
}
