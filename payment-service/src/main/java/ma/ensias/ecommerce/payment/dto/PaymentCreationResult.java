package ma.ensias.ecommerce.payment.dto;

/** Outcome of a payment creation call: the payment and whether it is an idempotent replay. */
public record PaymentCreationResult(PaymentResponse payment, boolean replayed) {
}
