package ma.ensias.ecommerce.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials to verify. Only presence and a maximum size are checked: the format rules of registration are
 * deliberately not repeated here so that a failure never reveals anything about the account.
 */
public record VerifyCredentialsRequest(
        @NotBlank @Size(max = 120) String email,
        @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
        // never print the password
        return "VerifyCredentialsRequest[email=" + email + "]";
    }
}
