package ma.ensias.ecommerce.payment.exception;

/** Raised when a payment is not in a state that allows the operation (HTTP 409). */
public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
