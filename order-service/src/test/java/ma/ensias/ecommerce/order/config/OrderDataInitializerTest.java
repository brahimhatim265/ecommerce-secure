package ma.ensias.ecommerce.order.config;

import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.entity.Product;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({OrderDataInitializer.class, ClockConfig.class})
class OrderDataInitializerTest {

    @Autowired
    private OrderDataInitializer initializer;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        initializer.run(null);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void loadsNineProductsWithTheCurrentStocksAndPrices() {
        assertThat(productRepository.count()).isEqualTo(9);

        Product playstation = productRepository.findBySku("PS5-SLIM-STD").orElseThrow();
        assertThat(playstation.getPrice()).isEqualByComparingTo("5990.00");
        assertThat(playstation.getStock()).isEqualTo(18);
        assertThat(playstation.getName()).isEqualTo("PlayStation 5 Slim Édition Standard");

        assertThat(productRepository.findBySku("PS5-DUALSENSE").orElseThrow().getStock()).isEqualTo(59);
        assertThat(productRepository.findBySku("IPH18-256").orElseThrow().getStock()).isEqualTo(14);
        assertThat(productRepository.findBySku("GS25U-256").orElseThrow().getStock()).isEqualTo(20);
        assertThat(productRepository.findBySku("LG-TV55-4K").orElseThrow().getStock()).isEqualTo(11);
        assertThat(productRepository.findBySku("MBA13-M4").orElseThrow().getStock()).isEqualTo(8);
        assertThat(productRepository.findBySku("APP-PRO2").orElseThrow().getStock()).isEqualTo(40);
        assertThat(productRepository.findBySku("SONY-XM5").orElseThrow().getStock()).isEqualTo(24);
        assertThat(productRepository.findBySku("CHG-65W").orElseThrow().getStock()).isEqualTo(150);
        assertThat(productRepository.findAll()).allSatisfy(p -> assertThat(p.getDescription()).isNotBlank());
    }

    @Test
    void loadsFiveConfirmedOrdersWhosePaymentIdEqualsTheOrderId() {
        List<Order> orders = orderRepository.findAll();

        assertThat(orders).hasSize(5);
        assertThat(orders).allSatisfy(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentId()).isEqualTo(order.getId());
            assertThat(order.getCurrency()).isEqualTo("MAD");
        });
    }

    @Test
    void ordersHaveTheExpectedOwnersTotalsAndMethods() {
        assertOrder("00000000-0000-4000-8000-000000000001", "6780.00", PaymentMethod.CARD, 2);
        assertOrder("00000000-0000-4000-8000-000000000002", "13990.00", PaymentMethod.CARD, 1);
        assertOrder("00000000-0000-4000-8000-000000000003", "5490.00", PaymentMethod.CARD, 1);
        assertOrder("00000000-0000-4000-8000-000000000004", "3539.00", PaymentMethod.CARD, 2);
        assertOrder("00000000-0000-4000-8000-000000000005", "2988.00", PaymentMethod.CASH_ON_DELIVERY, 2);
    }

    @Test
    void orderCreationDatesAreDistinctAndInThePast() {
        assertThat(orderRepository.findAll().stream().map(Order::getCreatedAt).distinct().count()).isEqualTo(5);
        assertThat(orderRepository.findAll()).allSatisfy(o -> assertThat(o.getCreatedAt()).isBefore(java.time.Instant.now()));
    }

    @Test
    void loadingTwiceDoesNotDuplicateAnything() {
        initializer.run(null);

        assertThat(productRepository.count()).isEqualTo(9);
        assertThat(orderRepository.count()).isEqualTo(5);
    }

    private void assertOrder(String owner, String total, PaymentMethod method, int lines) {
        List<Order> found = orderRepository.findByOwnerIdOrderByIdAsc(UUID.fromString(owner));
        assertThat(found).hasSize(1);
        Order order = found.get(0);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(total);
        assertThat(order.getPaymentMethod()).isEqualTo(method);
        assertThat(order.getItems()).hasSize(lines);
    }
}
