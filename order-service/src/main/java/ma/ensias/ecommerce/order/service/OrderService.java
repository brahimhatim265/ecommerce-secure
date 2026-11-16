package ma.ensias.ecommerce.order.service;

import ma.ensias.ecommerce.order.client.PaymentClient;
import ma.ensias.ecommerce.order.client.UserClient;
import ma.ensias.ecommerce.order.config.Currencies;
import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderItemRequest;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.dto.PaymentRequest;
import ma.ensias.ecommerce.order.dto.PaymentResult;
import ma.ensias.ecommerce.order.dto.UserSummary;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.exception.BusinessRuleException;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.InvalidRequestException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.mapper.OrderMapper;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Orchestrator of the order flow. {@link #createOrder} is deliberately NOT transactional: it chains
 * (1) user check over HTTP, (2) transaction A, (3) payment call over HTTP, (4) transaction B, and no HTTP call
 * ever happens inside a database transaction.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Attempts when giving stock back collides with a concurrent stock update (optimistic locking). */
    private static final int MAX_COMPENSATION_ATTEMPTS = 3;
    private static final String IDEMPOTENCY_KEY_PREFIX = "order-";

    private final UserClient userClient;
    private final PaymentClient paymentClient;
    private final OrderPlacementService placementService;
    private final OrderRepository orderRepository;
    private final OrderMapper mapper;

    public OrderService(UserClient userClient, PaymentClient paymentClient, OrderPlacementService placementService,
                        OrderRepository orderRepository, OrderMapper mapper) {
        this.userClient = userClient;
        this.paymentClient = paymentClient;
        this.placementService = placementService;
        this.orderRepository = orderRepository;
        this.mapper = mapper;
    }

    /**
     * Creates and pays an order. {@code ownerId} is passed explicitly (today read from the request body by the
     * controller, later taken from the JWT subject): this is the single place to change.
     */
    public OrderResponse createOrder(UUID ownerId, CreateOrderRequest request) {
        rejectDuplicateProducts(request);
        checkUser(ownerId);

        PlacedOrder placed = placementService.place(ownerId, request);   // transaction A

        PaymentResult payment;
        try {
            payment = paymentClient.createPayment(new PaymentRequest(placed.orderId(), ownerId,
                    placed.totalAmount(), Currencies.MAD, request.paymentMethod(), request.cardToken()),
                    IDEMPOTENCY_KEY_PREFIX + placed.orderId());
        } catch (InvalidRequestException e) {
            compensate(() -> placementService.markPaymentError(placed.orderId()));
            throw e; // 400 relayed with the original message
        } catch (ExternalServiceException e) {
            compensate(() -> placementService.markPaymentError(placed.orderId()));
            throw new ExternalServiceException(e.getMessage(), List.of("orderId: " + placed.orderId()));
        }

        return applyPaymentResult(placed.orderId(), request.paymentMethod(), payment);   // transaction B
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        return mapper.toResponse(orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id)));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(UUID ownerId) {
        var orders = ownerId == null
                ? orderRepository.findAll(Sort.by("id"))
                : orderRepository.findByOwnerIdOrderByIdAsc(ownerId);
        return orders.stream().map(mapper::toResponse).toList();
    }

    private OrderResponse applyPaymentResult(Long orderId, PaymentMethod method, PaymentResult payment) {
        String status = payment.status();
        if (PaymentResult.STATUS_FAILED.equals(status)) {
            return compensate(() -> placementService.refuse(orderId, payment.id(), payment.failureReason()));
        }
        boolean cardPaid = method == PaymentMethod.CARD && PaymentResult.STATUS_SUCCEEDED.equals(status);
        boolean cashPending = method == PaymentMethod.CASH_ON_DELIVERY && PaymentResult.STATUS_PENDING.equals(status);
        if (cardPaid || cashPending) {
            return placementService.confirm(orderId, payment.id());
        }
        log.error("Order {}: unexpected payment status '{}' for method {}", orderId, status, method);
        compensate(() -> placementService.markPaymentError(orderId));
        throw new ExternalServiceException("payment-service returned an unexpected payment status",
                List.of("orderId: " + orderId));
    }

    private void rejectDuplicateProducts(CreateOrderRequest request) {
        Set<Long> seen = new HashSet<>();
        for (OrderItemRequest item : request.items()) {
            if (!seen.add(item.productId())) {
                throw new InvalidRequestException("Duplicate product lines are not allowed",
                        List.of("items: productId " + item.productId() + " appears more than once"));
            }
        }
    }

    private void checkUser(UUID ownerId) {
        UserSummary user = userClient.getUser(ownerId);
        if (!user.active()) {
            throw new BusinessRuleException("User account is inactive");
        }
    }

    /** Runs a stock-restoring transaction, retrying when it collides with a concurrent stock update. */
    private <T> T compensate(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_COMPENSATION_ATTEMPTS) {
                    throw e;
                }
                log.warn("Stock restoration collided with a concurrent update, retrying ({}/{})", attempt,
                        MAX_COMPENSATION_ATTEMPTS);
            }
        }
    }
}
