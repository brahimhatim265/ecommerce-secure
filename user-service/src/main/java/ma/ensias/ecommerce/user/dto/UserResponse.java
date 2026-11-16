package ma.ensias.ecommerce.user.dto;

import ma.ensias.ecommerce.user.entity.Role;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Public view of a user: never contains a password or a hash. */
public record UserResponse(UUID id, String email, String firstName, String lastName, String phone, String address,
                           String city, Set<Role> roles, boolean active, Instant createdAt, Instant updatedAt) {
}
