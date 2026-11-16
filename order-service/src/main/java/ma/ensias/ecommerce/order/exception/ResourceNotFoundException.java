package ma.ensias.ecommerce.order.exception;

/** Raised when a resource does not exist (HTTP 404). */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
