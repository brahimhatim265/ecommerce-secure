package ma.ensias.ecommerce.order.service;

import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderItemRequest;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderItem;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.entity.Product;
import ma.ensias.ecommerce.order.exception.BusinessRuleException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.mapper.OrderMapper;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;

    private OrderPlacementService service;
    private Product playstation;
    private Product controller;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementService(orderRepository, productRepository, new OrderMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        playstation = product(1L, "PS5-SLIM-STD", "PlayStation 5 Slim", "5990.00", 18);
        controller = product(2L, "PS5-DUALSENSE", "Manette DualSense", "790.00", 59);
    }

    private static Product product(long id, String sku, String name, String price, int stock) {
        Product product = new Product(sku, name, "Sony", "Gaming", null, new BigDecimal(price), stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private static CreateOrderRequest request(OrderItemRequest... items) {
        return new CreateOrderRequest("  45 Rue Ibnou Katir  ", "Casablanca", PaymentMethod.CARD,
                "tok_visa_approved", List.of(items));
    }

    private Order pendingOrderWithStockReserved() {
        Order order = new Order(OWNER, OrderStatus.PENDING_PAYMENT, "45 Rue Ibnou Katir", "Casablanca",
                PaymentMethod.CARD, NOW);
        order.addItem(new OrderItem(1L, "PlayStation 5 Slim", new BigDecimal("5990.00"), 2));
        order.addItem(new OrderItem(2L, "Manette DualSense", new BigDecimal("790.00"), 1));
        ReflectionTestUtils.setField(order, "id", 6L);
        playstation.decreaseStock(2);
        controller.decreaseStock(1);
        return order;
    }

    @Test
    void placeReservesStockComputesTheTotalAndSnapshotsNamesAndPrices() {
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(playstation, controller));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 6L);
            return order;
        });

        PlacedOrder placed = service.place(OWNER,
                request(new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 2)));

        assertThat(placed.orderId()).isEqualTo(6L);
        assertThat(placed.totalAmount()).isEqualByComparingTo("7570.00"); // 5990 + 2 x 790
        assertThat(playstation.getStock()).isEqualTo(17);
        assertThat(controller.getStock()).isEqualTo(57);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(saved.getOwnerId()).isEqualTo(OWNER);
        assertThat(saved.getShippingAddress()).isEqualTo("45 Rue Ibnou Katir");
        assertThat(saved.getCurrency()).isEqualTo("MAD");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getItems().get(0).getProductName()).isEqualTo("PlayStation 5 Slim");
        assertThat(saved.getItems().get(0).getUnitPrice()).isEqualByComparingTo("5990.00");
        assertThat(saved.getItems().get(1).getQuantity()).isEqualTo(2);
    }

    @Test
    void placeFailsWhenAProductDoesNotExist() {
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(playstation));

        assertThatThrownBy(() -> service.place(OWNER,
                request(new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 1))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("2");
    }

    @Test
    void placeFailsWithTheProductNameWhenStockIsInsufficient() {
        Product macbook = product(6L, "MBA13-M4", "MacBook Air 13\" M4 256 Go", "12490.00", 8);
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(macbook));

        assertThatThrownBy(() -> service.place(OWNER, request(new OrderItemRequest(6L, 9))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("MacBook Air 13\" M4 256 Go")
                .hasMessageContaining("requested 9")
                .hasMessageContaining("available 8");
        assertThat(macbook.getStock()).isEqualTo(8);
    }

    @Test
    void confirmSetsConfirmedAndThePaymentId() {
        Order order = pendingOrderWithStockReserved();
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));

        OrderResponse response = service.confirm(6L, 11L);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.paymentId()).isEqualTo(11L);
        assertThat(playstation.getStock()).isEqualTo(16); // stock stays reserved
    }

    @Test
    void refuseRestoresTheStockAndCopiesTheReason() {
        Order order = pendingOrderWithStockReserved();
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(playstation, controller));

        OrderResponse response = service.refuse(6L, 12L, "INSUFFICIENT_FUNDS");

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_REFUSED);
        assertThat(response.paymentFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.paymentId()).isEqualTo(12L);
        assertThat(playstation.getStock()).isEqualTo(18);
        assertThat(controller.getStock()).isEqualTo(59);
    }

    @Test
    void markPaymentErrorRestoresTheStock() {
        Order order = pendingOrderWithStockReserved();
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(playstation, controller));

        OrderResponse response = service.markPaymentError(6L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_ERROR);
        assertThat(playstation.getStock()).isEqualTo(18);
        assertThat(controller.getStock()).isEqualTo(59);
    }

    @Test
    void compensationIsAppliedOnlyOnceToAPendingOrder() {
        Order order = pendingOrderWithStockReserved();
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(playstation, controller));

        service.markPaymentError(6L);
        service.refuse(6L, 12L, "CARD_DECLINED"); // already PAYMENT_ERROR: nothing more happens

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_ERROR);
        assertThat(playstation.getStock()).isEqualTo(18);
    }

    @Test
    void unknownOrderIsNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(99L, 1L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
