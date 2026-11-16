package ma.ensias.ecommerce.order.service;

import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderItemRequest;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderItem;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.Product;
import ma.ensias.ecommerce.order.exception.BusinessRuleException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.mapper.OrderMapper;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The short database transactions of the order flow. This is a separate bean from {@link OrderService} on purpose:
 * calling these methods through the Spring proxy guarantees they really run in a transaction (a self-invocation
 * would silently skip it), and no HTTP call is ever made inside them.
 */
@Service
public class OrderPlacementService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderMapper mapper;
    private final Clock clock;

    public OrderPlacementService(OrderRepository orderRepository, ProductRepository productRepository,
                                 OrderMapper mapper, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Transaction A: checks products and stock, reserves the stock, computes the total from the current prices and
     * saves the order as PENDING_PAYMENT with copies of product names and prices.
     */
    @Transactional
    public PlacedOrder place(UUID ownerId, CreateOrderRequest request) {
        List<Long> productIds = request.items().stream().map(OrderItemRequest::productId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(ownerId, OrderStatus.PENDING_PAYMENT, request.shippingAddress().trim(),
                request.shippingCity().trim(), request.paymentMethod(), clock.instant());

        for (OrderItemRequest line : request.items()) {
            Product product = products.get(line.productId());
            if (product == null) {
                throw new ResourceNotFoundException("Product not found: " + line.productId());
            }
            int quantity = line.quantity();
            if (!product.hasStockFor(quantity)) {
                throw new BusinessRuleException("Insufficient stock for product '" + product.getName()
                        + "' (requested " + quantity + ", available " + product.getStock() + ")");
            }
            product.decreaseStock(quantity);
            order.addItem(new OrderItem(product.getId(), product.getName(), product.getPrice(), quantity));
        }

        // flush now: an optimistic-locking conflict on a product surfaces here and the whole reservation rolls back
        Order saved = orderRepository.saveAndFlush(order);
        log.info("Order {} placed for user {}: total={} MAD, status=PENDING_PAYMENT", saved.getId(), ownerId,
                saved.getTotalAmount());
        return new PlacedOrder(saved.getId(), saved.getTotalAmount());
    }

    /** Transaction B (success): the order becomes CONFIRMED. */
    @Transactional
    public OrderResponse confirm(Long orderId, Long paymentId) {
        Order order = load(orderId);
        if (order.isPendingPayment()) {
            order.markConfirmed(paymentId, clock.instant());
            log.info("Order {} confirmed (payment {})", orderId, paymentId);
        }
        return mapper.toResponse(order);
    }

    /** Transaction B (card refused): PAYMENT_REFUSED with the reason, and the reserved stock is given back. */
    @Transactional
    public OrderResponse refuse(Long orderId, Long paymentId, String failureReason) {
        Order order = load(orderId);
        if (order.isPendingPayment()) {
            order.markPaymentRefused(paymentId, failureReason, clock.instant());
            restock(order);
            log.info("Order {} refused by the payment provider ({}), stock restored", orderId, failureReason);
        }
        return mapper.toResponse(order);
    }

    /** Transaction B (technical failure or invalid payment request): PAYMENT_ERROR and stock restored. */
    @Transactional
    public OrderResponse markPaymentError(Long orderId) {
        Order order = load(orderId);
        if (order.isPendingPayment()) {
            order.markPaymentError(clock.instant());
            restock(order);
            log.warn("Order {} set to PAYMENT_ERROR, stock restored", orderId);
        }
        return mapper.toResponse(order);
    }

    private Order load(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    private void restock(Order order) {
        Map<Long, Integer> quantities = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        for (Product product : productRepository.findAllById(quantities.keySet())) {
            product.increaseStock(quantities.get(product.getId()));
        }
    }
}
