package ma.ensias.ecommerce.order.dto;

import ma.ensias.ecommerce.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.UUID;

/** Body sent to payment-service {@code POST /payments}. */
public record PaymentRequest(Long orderId, UUID ownerId, BigDecimal amount, String currency, PaymentMethod method,
                             String cardToken) {
}
