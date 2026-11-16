package ma.ensias.ecommerce.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import ma.ensias.ecommerce.payment.config.Currencies;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A simulated payment. The card token itself is never stored: only the brand, the last 4 digits and the outcome.
 * Uniqueness of {@code orderId} and {@code idempotencyKey} is enforced by the database.
 */
@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_reference", columnNames = "reference"),
        @UniqueConstraint(name = "uk_payments_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payments_idempotency_key", columnNames = "idempotency_key")
})
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference", nullable = false, length = 20)
    private String reference;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Currencies.MAD;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Version
    private Long version;

    protected Payment() {
        // required by JPA
    }

    public Payment(String reference, Long orderId, UUID ownerId, BigDecimal amount, PaymentMethod method,
                   PaymentStatus status, FailureReason failureReason, String cardBrand, String cardLast4,
                   String idempotencyKey, String requestFingerprint, Instant createdAt) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.method = Objects.requireNonNull(method, "method");
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.cardBrand = cardBrand;
        this.cardLast4 = cardLast4;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    /** Cash on delivery collected: PENDING becomes SUCCEEDED. The caller must have checked the state. */
    public void markCollected(Instant now) {
        this.status = PaymentStatus.SUCCEEDED;
        this.collectedAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Long getOrderId() {
        return orderId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Payment other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Payment{id=" + id + ", reference=" + reference + ", status=" + status + "}";
    }
}
