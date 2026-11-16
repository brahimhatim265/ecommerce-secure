package ma.ensias.ecommerce.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ma.ensias.ecommerce.payment.config.Currencies;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.UUID;

/** Payment request sent by the order-service. {@code cardToken} is a public test token, never a card number. */
@ConsistentCardToken
public record CreatePaymentRequest(
        @NotNull @Positive Long orderId,
        @NotNull UUID ownerId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^" + Currencies.MAD + "$") String currency,
        @NotNull PaymentMethod method,
        @Size(max = 60) @Pattern(regexp = "^[a-z0-9_]+$") String cardToken) {
}
