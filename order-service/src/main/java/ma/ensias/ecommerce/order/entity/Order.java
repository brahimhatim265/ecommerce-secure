package ma.ensias.ecommerce.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import ma.ensias.ecommerce.order.config.Currencies;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer order. {@code ownerId} is the user's UUID (later compared with the JWT {@code sub} claim);
 * no email or name is stored here.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Currencies.MAD;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    @Column(name = "shipping_city", nullable = false, length = 60)
    private String shippingCity;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "payment_failure_reason", length = 40)
    private String paymentFailureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // required by JPA
    }

    public Order(UUID ownerId, OrderStatus status, String shippingAddress, String shippingCity,
                 PaymentMethod paymentMethod, Instant createdAt) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.status = Objects.requireNonNull(status, "status");
        this.shippingAddress = Objects.requireNonNull(shippingAddress, "shippingAddress");
        this.shippingCity = Objects.requireNonNull(shippingCity, "shippingCity");
        this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    /** Adds a line and keeps the total in sync. */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
        totalAmount = totalAmount.add(item.lineTotal());
    }

    public boolean isPendingPayment() {
        return status == OrderStatus.PENDING_PAYMENT;
    }

    public void markConfirmed(Long paymentId, Instant now) {
        this.status = OrderStatus.CONFIRMED;
        this.paymentId = paymentId;
        this.updatedAt = now;
    }

    public void markPaymentRefused(Long paymentId, String failureReason, Instant now) {
        this.status = OrderStatus.PAYMENT_REFUSED;
        this.paymentId = paymentId;
        this.paymentFailureReason = failureReason;
        this.updatedAt = now;
    }

    public void markPaymentError(Instant now) {
        this.status = OrderStatus.PAYMENT_ERROR;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public String getShippingCity() {
        return shippingCity;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getPaymentFailureReason() {
        return paymentFailureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order other)) {
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
        return "Order{id=" + id + ", status=" + status + "}";
    }
}
