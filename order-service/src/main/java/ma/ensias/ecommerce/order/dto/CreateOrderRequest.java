package ma.ensias.ecommerce.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.ensias.ecommerce.order.entity.PaymentMethod;

import java.util.List;

/**
 * Order creation request. The owner of the order is NOT part of the body: it is the claim {@code sub} of the token.
 * A body that contains {@code ownerId} is rejected as an unknown property. {@code cardToken} is a public test token,
 * never a card number.
 */
@ConsistentCardToken
public record CreateOrderRequest(
        @NotBlank @Size(min = 5, max = 255) String shippingAddress,
        @NotBlank @Size(max = 60) String shippingCity,
        @NotNull PaymentMethod paymentMethod,
        @Size(max = 60) @Pattern(regexp = "^[a-z0-9_]+$") String cardToken,
        @NotEmpty @Size(max = 50) List<@Valid OrderItemRequest> items) {
}
