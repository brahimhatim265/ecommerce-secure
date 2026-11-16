package ma.ensias.ecommerce.order.dto;

import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(Long id, UUID ownerId, OrderStatus status, BigDecimal totalAmount, String currency,
                            String shippingAddress, String shippingCity, PaymentMethod paymentMethod,
                            Long paymentId, String paymentFailureReason, List<OrderItemResponse> items,
                            Instant createdAt, Instant updatedAt) {
}
