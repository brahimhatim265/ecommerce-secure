package ma.ensias.ecommerce.payment.exception;

/** Raised when a unique value or an idempotency key is already used (HTTP 409). */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
