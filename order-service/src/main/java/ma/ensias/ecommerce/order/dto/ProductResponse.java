package ma.ensias.ecommerce.order.dto;

import java.math.BigDecimal;

public record ProductResponse(Long id, String sku, String name, String brand, String category, String description,
                              BigDecimal price, int stock) {
}
