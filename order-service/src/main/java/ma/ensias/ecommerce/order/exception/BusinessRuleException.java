package ma.ensias.ecommerce.order.exception;

/** Raised when a business rule is violated, e.g. insufficient stock (HTTP 422). */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
