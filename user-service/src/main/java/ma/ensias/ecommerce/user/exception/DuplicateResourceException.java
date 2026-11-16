package ma.ensias.ecommerce.user.exception;

/** Raised when a unique value is already used (HTTP 409). */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
