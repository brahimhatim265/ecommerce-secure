package ma.ensias.ecommerce.user.exception;

/** Unknown account or wrong password: the message is identical in both cases (HTTP 401). */
public class InvalidCredentialsException extends RuntimeException {

    public static final String MESSAGE = "Invalid credentials";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
