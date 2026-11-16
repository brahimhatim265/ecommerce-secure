package ma.ensias.ecommerce.order.service;

import ma.ensias.ecommerce.order.client.PaymentClient;
import ma.ensias.ecommerce.order.client.UserClient;
import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderItemRequest;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.dto.PaymentRequest;
import ma.ensias.ecommerce.order.dto.PaymentResult;
import ma.ensias.ecommerce.order.dto.UserSummary;
import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.exception.BusinessRuleException;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.InvalidRequestException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.mapper.OrderMapper;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Long ORDER_ID = 6L;
    private static final BigDecimal TOTAL = new BigDecimal("6780.00");

    @Mock
    private UserClient userClient;
    @Mock
    private PaymentClient paymentClient;
    @Mock
    private OrderPlacementService placementService;
    @Mock
    private OrderRepository orderRepository;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(userClient, paymentClient, placementService, orderRepository, new OrderMapper());
    }

    private static CreateOrderRequest request(PaymentMethod method, String token) {
        return new CreateOrderRequest(OWNER, "45 Rue Ibnou Katir, Maârif", "Casablanca", method, token,
                List.of(new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 1)));
    }

    private static OrderResponse response(OrderStatus status) {
        return new OrderResponse(ORDER_ID, OWNER, status, TOTAL, "MAD", "addr", "city", PaymentMethod.CARD, null,
                null, List.of(), Instant.now(), Instant.now());
    }

    private void activeUser() {
        when(userClient.getUser(OWNER)).thenReturn(new UserSummary(OWNER, true));
    }

    private void placed() {
        when(placementService.place(eq(OWNER), any(CreateOrderRequest.class)))
                .thenReturn(new PlacedOrder(ORDER_ID, TOTAL));
    }

    @Test
    void cardPaymentSucceededConfirmsTheOrder() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenReturn(new PaymentResult(11L, "SUCCEEDED", null));
        when(placementService.confirm(ORDER_ID, 11L)).thenReturn(response(OrderStatus.CONFIRMED));

        OrderResponse result = service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_visa_approved"));

        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
        ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(paymentClient).createPayment(captor.capture(), eq("order-6"));
        PaymentRequest sent = captor.getValue();
        assertThat(sent.orderId()).isEqualTo(ORDER_ID);
        assertThat(sent.ownerId()).isEqualTo(OWNER);
        assertThat(sent.amount()).isEqualByComparingTo(TOTAL);
        assertThat(sent.currency()).isEqualTo("MAD");
        assertThat(sent.method()).isEqualTo(PaymentMethod.CARD);
        assertThat(sent.cardToken()).isEqualTo("tok_visa_approved");
    }

    @Test
    void failedPaymentRefusesTheOrderWithTheReasonAndStillReturnsTheOrder() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenReturn(new PaymentResult(12L, "FAILED", "INSUFFICIENT_FUNDS"));
        when(placementService.refuse(ORDER_ID, 12L, "INSUFFICIENT_FUNDS"))
                .thenReturn(response(OrderStatus.PAYMENT_REFUSED));

        OrderResponse result = service.createOrder(OWNER,
                request(PaymentMethod.CARD, "tok_declined_insufficient_funds"));

        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_REFUSED);
        verify(placementService, never()).confirm(anyLong(), any());
    }

    @Test
    void cashOnDeliveryWithAPendingPaymentConfirmsTheOrder() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenReturn(new PaymentResult(13L, "PENDING", null));
        when(placementService.confirm(ORDER_ID, 13L)).thenReturn(response(OrderStatus.CONFIRMED));

        OrderResponse result = service.createOrder(OWNER, request(PaymentMethod.CASH_ON_DELIVERY, null));

        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void unexpectedPaymentStatusSetsPaymentErrorAndFailsWith503() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenReturn(new PaymentResult(14L, "PENDING", null)); // PENDING is impossible for a card

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_visa_approved")))
                .isInstanceOf(ExternalServiceException.class);
        verify(placementService).markPaymentError(ORDER_ID);
        verify(placementService, never()).confirm(anyLong(), any());
    }

    @Test
    void paymentRejectedWith400RestoresTheStockAndRelaysTheOriginalMessage() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenThrow(new InvalidRequestException("Unknown card token"));

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_nope")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Unknown card token");
        verify(placementService).markPaymentError(ORDER_ID);
    }

    @Test
    void technicalPaymentFailureSetsPaymentErrorAndReportsTheOrderId() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenThrow(new ExternalServiceException("payment-service is unreachable or timed out"));

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_visa_approved")))
                .isInstanceOfSatisfying(ExternalServiceException.class,
                        e -> assertThat(e.getDetails()).containsExactly("orderId: 6"));
        verify(placementService).markPaymentError(ORDER_ID);
    }

    @Test
    void unknownUserStopsBeforeAnyReservation() {
        when(userClient.getUser(OWNER)).thenThrow(new ResourceNotFoundException("User not found"));

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_visa_approved")))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(placementService, paymentClient);
    }

    @Test
    void userServiceFailureGives503BeforeAnyReservation() {
        when(userClient.getUser(OWNER)).thenThrow(new ExternalServiceException("user-service is unreachable"));

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_visa_approved")))
                .isInstanceOf(ExternalServiceException.class);
        verifyNoInteractions(placementService, paymentClient);
    }

    @Test
    void inactiveUserIsRefused() {
        when(userClient.getUser(OWNER)).thenReturn(new UserSummary(OWNER, false));

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_visa_approved")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("User account is inactive");
        verifyNoInteractions(placementService, paymentClient);
    }

    @Test
    void duplicateProductLinesAreRefusedFirst() {
        CreateOrderRequest duplicated = new CreateOrderRequest(OWNER, "45 Rue Ibnou Katir", "Casablanca",
                PaymentMethod.CARD, "tok_visa_approved",
                List.of(new OrderItemRequest(1L, 1), new OrderItemRequest(1L, 2)));

        assertThatThrownBy(() -> service.createOrder(OWNER, duplicated))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Duplicate");
        verifyNoInteractions(userClient, placementService, paymentClient);
    }

    @Test
    void stockRestorationIsRetriedAfterAnOptimisticLockingCollision() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenReturn(new PaymentResult(12L, "FAILED", "CARD_DECLINED"));
        when(placementService.refuse(ORDER_ID, 12L, "CARD_DECLINED"))
                .thenThrow(new OptimisticLockingFailureException("collision"))
                .thenReturn(response(OrderStatus.PAYMENT_REFUSED));

        OrderResponse result = service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_declined_generic"));

        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_REFUSED);
        verify(placementService, times(2)).refuse(ORDER_ID, 12L, "CARD_DECLINED");
    }

    @Test
    void stockRestorationGivesUpAfterThreeCollisions() {
        activeUser();
        placed();
        when(paymentClient.createPayment(any(PaymentRequest.class), eq("order-6")))
                .thenReturn(new PaymentResult(12L, "FAILED", "CARD_DECLINED"));
        when(placementService.refuse(ORDER_ID, 12L, "CARD_DECLINED"))
                .thenThrow(new OptimisticLockingFailureException("collision"));

        assertThatThrownBy(() -> service.createOrder(OWNER, request(PaymentMethod.CARD, "tok_declined_generic")))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(placementService, times(3)).refuse(ORDER_ID, 12L, "CARD_DECLINED");
    }

    @Test
    void getOrderReturnsTheOrderOrFailsWhenUnknown() {
        Order order = new Order(OWNER, OrderStatus.CONFIRMED, "addr12345", "Casablanca", PaymentMethod.CARD,
                Instant.now());
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.getOrder(1L).ownerId()).isEqualTo(OWNER);
        assertThatThrownBy(() -> service.getOrder(2L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listOrdersFiltersByOwner() {
        Order order = new Order(OWNER, OrderStatus.CONFIRMED, "addr12345", "Casablanca", PaymentMethod.CARD,
                Instant.now());
        when(orderRepository.findByOwnerIdOrderByIdAsc(OWNER)).thenReturn(List.of(order));

        assertThat(service.listOrders(OWNER)).hasSize(1);
    }
}
