package ma.ensias.ecommerce.order.repository;

import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderItem;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.entity.Product;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class OrderRepositoriesTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private EntityManager entityManager;

    private Product newProduct(String sku, String name, String category) {
        return new Product(sku, name, "Sony", category, null, new BigDecimal("100.00"), 10);
    }

    @Test
    void categoryFilterIsCaseInsensitiveAndOrderedById() {
        productRepository.saveAndFlush(newProduct("A", "Product A", "Gaming"));
        productRepository.saveAndFlush(newProduct("B", "Product B", "Audio"));
        productRepository.saveAndFlush(newProduct("C", "Product C", "Gaming"));

        assertThat(productRepository.findByCategoryIgnoreCaseOrderByIdAsc("gaming"))
                .extracting(Product::getSku).containsExactly("A", "C");
    }

    @Test
    void skuAndNameExistenceChecks() {
        Product saved = productRepository.saveAndFlush(newProduct("A", "Product A", "Gaming"));

        assertThat(productRepository.existsBySku("A")).isTrue();
        assertThat(productRepository.existsByName("Product A")).isTrue();
        assertThat(productRepository.existsBySkuAndIdNot("A", saved.getId())).isFalse();
        assertThat(productRepository.existsBySkuAndIdNot("A", saved.getId() + 1)).isTrue();
        assertThat(productRepository.existsByNameAndIdNot("Product A", saved.getId())).isFalse();
        assertThat(productRepository.findBySku("A")).isPresent();
    }

    @Test
    void skuAndNameMustBeUnique() {
        productRepository.saveAndFlush(newProduct("A", "Product A", "Gaming"));

        assertThatThrownBy(() -> productRepository.saveAndFlush(newProduct("A", "Other name", "Gaming")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nameMustBeUnique() {
        productRepository.saveAndFlush(newProduct("A", "Product A", "Gaming"));

        assertThatThrownBy(() -> productRepository.saveAndFlush(newProduct("B", "Product A", "Gaming")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentStockUpdatesAreDetectedByOptimisticLocking() {
        Product saved = productRepository.saveAndFlush(newProduct("A", "Product A", "Gaming"));
        Long id = saved.getId();
        entityManager.clear();

        Product firstView = productRepository.findById(id).orElseThrow();
        entityManager.detach(firstView);
        Product secondView = productRepository.findById(id).orElseThrow();
        secondView.decreaseStock(1);
        productRepository.saveAndFlush(secondView);

        firstView.decreaseStock(2);
        assertThatThrownBy(() -> productRepository.saveAndFlush(firstView))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void ordersAreSavedWithTheirItemsAndFilteredByOwner() {
        Order order = new Order(OWNER, OrderStatus.PENDING_PAYMENT, "45 Rue Ibnou Katir", "Casablanca",
                PaymentMethod.CARD, Instant.parse("2026-11-14T10:00:00Z"));
        order.addItem(new OrderItem(1L, "Product A", new BigDecimal("100.00"), 2));
        order.addItem(new OrderItem(2L, "Product B", new BigDecimal("50.50"), 1));
        orderRepository.saveAndFlush(order);
        orderRepository.saveAndFlush(new Order(OTHER, OrderStatus.CONFIRMED, "12 Rue Oum Rabia", "Rabat",
                PaymentMethod.CASH_ON_DELIVERY, Instant.parse("2026-11-14T11:00:00Z")));
        entityManager.clear();

        var found = orderRepository.findByOwnerIdOrderByIdAsc(OWNER);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getItems()).hasSize(2);
        assertThat(found.get(0).getTotalAmount()).isEqualByComparingTo("250.50");
        assertThat(found.get(0).getCurrency()).isEqualTo("MAD");
    }
}
