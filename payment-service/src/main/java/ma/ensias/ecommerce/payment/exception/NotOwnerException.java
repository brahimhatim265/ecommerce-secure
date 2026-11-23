package ma.ensias.ecommerce.payment.exception;

/**
 * The resource exists but belongs to someone else. It is answered exactly like a missing resource (404 with the same
 * message): payment and order identifiers are sequential, so a 403 would tell an attacker which numbers exist. The
 * audit log keeps the real reason (not_owner).
 */
public class NotOwnerException extends ResourceNotFoundException {

    public NotOwnerException(String message) {
        super(message);
    }
}
