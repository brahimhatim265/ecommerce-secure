package ma.ensias.ecommerce.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.nio.charset.StandardCharsets;

/** Registration request. The account is always created with the CUSTOMER role. */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(min = 8, max = 72) @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$") String password,
        @NotBlank @Size(max = 60) @Pattern(regexp = "^\\p{L}[\\p{L} '’-]*$") String firstName,
        @NotBlank @Size(max = 60) @Pattern(regexp = "^\\p{L}[\\p{L} '’-]*$") String lastName,
        @Pattern(regexp = "^\\+?[0-9 ()-]{6,20}$") String phone,
        @Size(max = 255) String address,
        @Size(max = 60) String city) {

    private static final int BCRYPT_MAX_BYTES = 72;

    public CreateUserRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** BCrypt only reads the first 72 bytes: reject longer passwords instead of silently truncating them. */
    @JsonIgnore
    @AssertTrue(message = "must not exceed 72 bytes once encoded in UTF-8")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }

    @Override
    public String toString() {
        // never print the password
        return "CreateUserRequest[email=" + email + "]";
    }
}
