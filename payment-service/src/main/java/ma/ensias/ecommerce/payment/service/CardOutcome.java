package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.entity.FailureReason;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;

/** Result of a simulated card authorization: status, public card details and, on failure, the reason. */
public record CardOutcome(PaymentStatus status, String brand, String last4, FailureReason failureReason) {
}
