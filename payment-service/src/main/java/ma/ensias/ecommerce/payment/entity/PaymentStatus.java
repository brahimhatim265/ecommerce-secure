package ma.ensias.ecommerce.payment.entity;

/** PENDING: cash on delivery awaiting collection. SUCCEEDED: paid or collected. FAILED: refused. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
