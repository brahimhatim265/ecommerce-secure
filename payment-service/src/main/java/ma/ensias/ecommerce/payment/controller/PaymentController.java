package ma.ensias.ecommerce.payment.controller;

import ma.ensias.ecommerce.payment.security.AccessRules;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.ensias.ecommerce.payment.dto.CreatePaymentRequest;
import ma.ensias.ecommerce.payment.dto.PaymentCreationResult;
import ma.ensias.ecommerce.payment.dto.PaymentResponse;
import ma.ensias.ecommerce.payment.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** REST endpoints of the simulated payment provider. No business rule here: see {@link PaymentService}. */
@RestController
@RequestMapping("/payments")
@Validated
@Tag(name = "Payments", description = "Sandbox payment provider: test card tokens and cash on delivery")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService paymentService;

    private final AccessRules access;

    public PaymentController(PaymentService paymentService, AccessRules access) {
        this.paymentService = paymentService;
        this.access = access;
    }

    // Matrice d'accès P1 : appel interne de order-service, pour le compte d'un utilisateur (JWT relayé).
    // Règle ABAC A5 : le propriétaire du paiement doit être l'utilisateur du jeton relayé.
    @PreAuthorize("@callers.isService('SERVICE_ORDER') and isAuthenticated() "
            + "and @access.isOwner(authentication, #request.ownerId())")
    @PostMapping
    @Operation(summary = "Process a payment (idempotent)",
            description = "Sandbox mode: the card token decides the outcome. Available test tokens: "
                    + "tok_visa_approved (SUCCEEDED, VISA 4242), tok_mastercard_approved (SUCCEEDED, MASTERCARD 4444), "
                    + "tok_declined_insufficient_funds (FAILED, VISA 9995), tok_declined_expired_card (FAILED, VISA 0069), "
                    + "tok_declined_generic (FAILED, VISA 0002), tok_random (weighted random outcome, drawn once). "
                    + "A refused card is a normal business result: 201 with status FAILED. "
                    + "Cash on delivery (CASH_ON_DELIVERY, no token) creates a PENDING payment. "
                    + "Replaying the same Idempotency-Key with the same parameters returns the stored payment "
                    + "with 200 and the header 'Idempotent-Replayed: true'.")
    @ApiResponse(responseCode = "201", description = "Payment created (status may be FAILED)")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an existing payment")
    @ApiResponse(responseCode = "400", description = "Validation error, unknown card token or missing Idempotency-Key")
    @ApiResponse(responseCode = "409", description = "Key reused with other parameters, or order already paid")
    public ResponseEntity<PaymentResponse> create(
            @Parameter(description = "Unique key chosen by the caller (1-64 chars: letters, digits, . _ : -)",
                    example = "order-42")
            @RequestHeader(IDEMPOTENCY_KEY_HEADER)
            @Size(min = 1, max = 64)
            @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentCreationResult result = paymentService.createPayment(request, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(result.payment());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(result.payment().id()).toUri();
        return ResponseEntity.created(location).body(result.payment());
    }

    // Matrice d'accès P2 : lecture d'un paiement, par son propriétaire ou par un administrateur (règle ABAC A3).
    // Pour un autre client, la réponse est un 404 identique à celle d'un paiement inexistant (décision D2).
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by id")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    public PaymentResponse get(@PathVariable Long id, Authentication authentication) {
        return paymentService.getPayment(id, owner -> access.isOwnerOrAdmin(authentication, owner));
    }

    // Matrice d'accès P3 : paiement d'une commande, par son propriétaire ou par un administrateur (règle ABAC A3, décision D2)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get the payment of an order")
    @ApiResponse(responseCode = "404", description = "No payment for this order")
    public PaymentResponse getByOrder(@PathVariable Long orderId, Authentication authentication) {
        return paymentService.getPaymentByOrder(orderId, owner -> access.isOwnerOrAdmin(authentication, owner));
    }

    // Matrice d'accès P4 : liste des paiements, filtrée sur l'identité de l'appelant (règle ABAC A4)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @GetMapping
    @Operation(summary = "List payments, optionally filtered by owner")
    public List<PaymentResponse> list(@RequestParam(required = false) UUID ownerId, Authentication authentication) {
        return paymentService.listPayments(access.ownerFilterFor(authentication, ownerId));
    }

    // Matrice d'accès P5 : encaissement à la livraison : administration
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/collect")
    @Operation(summary = "Collect a cash-on-delivery payment",
            description = "Only a CASH_ON_DELIVERY payment in status PENDING can be collected (it becomes SUCCEEDED).")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Not collectable (card payment or already collected)")
    public PaymentResponse collect(@PathVariable Long id) {
        return paymentService.collect(id);
    }
}
