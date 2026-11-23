package ma.ensias.ecommerce.order.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** REST endpoints of the orders. No business rule here: see {@link OrderService}. */
@RestController
@RequestMapping("/orders")
@Validated
@Tag(name = "Orders", description = "Order creation (with payment) and consultation")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // Matrice d'accès O5 : un client passe une commande ; un administrateur ne le peut pas (D1)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    @Operation(summary = "Create and pay an order",
            description = "Checks the user (user-service), reserves the stock, pays through payment-service and "
                    + "confirms the order. A refused card still returns 201 with status PAYMENT_REFUSED "
                    + "(stock restored). Use paymentMethod CARD with a test cardToken (tok_visa_approved, "
                    + "tok_mastercard_approved, tok_declined_insufficient_funds, tok_declined_expired_card, "
                    + "tok_declined_generic, tok_random) or CASH_ON_DELIVERY without token. "
                    + "ownerId is temporarily read from the body (it will come from the JWT later).")
    @ApiResponse(responseCode = "201", description = "Order created (check its status)")
    @ApiResponse(responseCode = "400", description = "Validation error or payment request rejected")
    @ApiResponse(responseCode = "404", description = "Unknown user or product")
    @ApiResponse(responseCode = "422", description = "Inactive user or insufficient stock")
    @ApiResponse(responseCode = "503", description = "user-service or payment-service unavailable")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        // ownerId is read here ONLY and passed explicitly: the single spot to change when it comes from the JWT
        OrderResponse created = orderService.createOrder(request.ownerId(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // Matrice d'accès O6 : lecture d'une commande (le propriétaire sera vérifié par la règle ABAC A2)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping("/{id}")
    @Operation(summary = "Get an order by id")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    // Matrice d'accès O7 : liste des commandes (filtrée par la règle ABAC A4)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping
    @Operation(summary = "List orders, optionally filtered by owner (UUID)")
    public List<OrderResponse> list(@RequestParam(required = false) UUID ownerId) {
        return orderService.listOrders(ownerId);
    }
}
