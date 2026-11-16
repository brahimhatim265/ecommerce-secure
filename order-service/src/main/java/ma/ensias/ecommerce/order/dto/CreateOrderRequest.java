package ma.ensias.ecommerce.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.ensias.ecommerce.order.entity.PaymentMethod;

import java.util.List;
import java.util.UUID;

/**
 * Order creation request. {@code ownerId} is temporarily part of the body; it will later come from the JWT
 * {@code sub} claim. {@code cardToken} is a public test token, never a card number.
 */
@ConsistentCardToken
public record CreateOrderRequest(
        @NotNull UUID ownerId,
        @NotBlank @Size(min = 5, max = 255) String shippingAddress,
        @NotBlank @Size(max = 60) String shippingCity,
        @NotNull PaymentMethod paymentMethod,
        @Size(max = 60) @Pattern(regexp = "^[a-z0-9_]+$") String cardToken,
        @NotEmpty @Size(max = 50) List<@Valid OrderItemRequest> items) {
}
