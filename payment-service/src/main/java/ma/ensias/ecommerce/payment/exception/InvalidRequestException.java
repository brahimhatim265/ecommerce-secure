package ma.ensias.ecommerce.payment.exception;

/** Raised when a request is well-formed JSON but semantically refused, e.g. an unknown card token (HTTP 400). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
