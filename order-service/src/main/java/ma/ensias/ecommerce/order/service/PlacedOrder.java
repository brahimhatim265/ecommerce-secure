package ma.ensias.ecommerce.order.service;

import java.math.BigDecimal;

/** What the orchestrator needs once the order is saved and the stock reserved (transaction A). */
public record PlacedOrder(Long orderId, BigDecimal totalAmount) {
}
