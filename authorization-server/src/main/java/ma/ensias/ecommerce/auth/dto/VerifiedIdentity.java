package ma.ensias.ecommerce.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VerifiedIdentity(UUID id, String email, String firstName, String lastName, Set<String> roles) {
}