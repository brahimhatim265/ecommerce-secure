package ma.ensias.ecommerce.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Profile update. The email, roles and active flag are intentionally absent (unknown properties are rejected). */
public record UpdateUserRequest(
        @NotBlank @Size(max = 60) @Pattern(regexp = "^\\p{L}[\\p{L} '’-]*$") String firstName,
        @NotBlank @Size(max = 60) @Pattern(regexp = "^\\p{L}[\\p{L} '’-]*$") String lastName,
        @Pattern(regexp = "^\\+?[0-9 ()-]{6,20}$") String phone,
        @Size(max = 255) String address,
        @Size(max = 60) String city) {
}
