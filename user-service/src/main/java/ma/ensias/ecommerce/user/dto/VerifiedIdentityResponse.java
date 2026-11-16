package ma.ensias.ecommerce.user.dto;

import ma.ensias.ecommerce.user.entity.Role;

import java.util.Set;
import java.util.UUID;

/** Identity returned when credentials are valid. Never contains the hash. */
public record VerifiedIdentityResponse(UUID id, String email, String firstName, String lastName, Set<Role> roles) {
}
