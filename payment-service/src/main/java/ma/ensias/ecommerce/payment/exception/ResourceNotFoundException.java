package ma.ensias.ecommerce.payment.exception;

/** Raised when a resource does not exist (HTTP 404). */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
