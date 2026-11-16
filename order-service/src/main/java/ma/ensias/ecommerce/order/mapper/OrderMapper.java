package ma.ensias.ecommerce.order.mapper;

import ma.ensias.ecommerce.order.dto.OrderItemResponse;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.dto.ProductResponse;
import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderItem;
import ma.ensias.ecommerce.order.entity.Product;
import org.springframework.stereotype.Component;

import java.util.List;

/** Hand-written entity to DTO conversions. Must be called while the entities are still attached (lazy items). */
@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(this::toResponse).toList();
        return new OrderResponse(order.getId(), order.getOwnerId(), order.getStatus(), order.getTotalAmount(),
                order.getCurrency(), order.getShippingAddress(), order.getShippingCity(), order.getPaymentMethod(),
                order.getPaymentId(), order.getPaymentFailureReason(), items, order.getCreatedAt(),
                order.getUpdatedAt());
    }

    public OrderItemResponse toResponse(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getProductName(), item.getUnitPrice(),
                item.getQuantity(), item.lineTotal());
    }

    public ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(), product.getBrand(),
                product.getCategory(), product.getDescription(), product.getPrice(), product.getStock());
    }
}
