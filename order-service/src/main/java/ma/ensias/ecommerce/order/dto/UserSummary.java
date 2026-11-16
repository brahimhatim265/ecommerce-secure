package ma.ensias.ecommerce.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** The part of a user-service response that order-service needs (tolerant to additional fields). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSummary(UUID id, boolean active) {
}
