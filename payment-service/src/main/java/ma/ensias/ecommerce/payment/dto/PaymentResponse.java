package ma.ensias.ecommerce.payment.dto;

import ma.ensias.ecommerce.payment.entity.FailureReason;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(Long id, String reference, Long orderId, UUID ownerId, BigDecimal amount,
                              String currency, PaymentMethod method, PaymentStatus status,
                              FailureReason failureReason, String cardBrand, String cardLast4,
                              Instant createdAt, Instant updatedAt, Instant collectedAt) {
}
