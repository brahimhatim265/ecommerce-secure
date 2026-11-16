package ma.ensias.ecommerce.user.exception;

/** Correct password but the account is deactivated (HTTP 403). */
public class AccountDisabledException extends RuntimeException {

    public static final String MESSAGE = "Account is disabled";

    public AccountDisabledException() {
        super(MESSAGE);
    }
}
