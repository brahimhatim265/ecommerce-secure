package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.dto.CreatePaymentRequest;
import ma.ensias.ecommerce.payment.dto.PaymentCreationResult;
import ma.ensias.ecommerce.payment.dto.PaymentResponse;
import ma.ensias.ecommerce.payment.entity.Payment;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import ma.ensias.ecommerce.payment.exception.DuplicateResourceException;
import ma.ensias.ecommerce.payment.exception.InvalidPaymentStateException;
import ma.ensias.ecommerce.payment.exception.InvalidRequestException;
import ma.ensias.ecommerce.payment.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.payment.mapper.PaymentMapper;
import ma.ensias.ecommerce.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

/**
 * Simulated payment provider. Every payment is idempotent on its {@code Idempotency-Key}: a replay with the
 * same parameters returns the stored payment (no new row, no new random draw).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentOutcomeRandomizer randomizer;
    private final PaymentReferenceGenerator referenceGenerator;
    private final PaymentMapper paymentMapper;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository, PaymentOutcomeRandomizer randomizer,
                          PaymentReferenceGenerator referenceGenerator, PaymentMapper paymentMapper, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.randomizer = randomizer;
        this.referenceGenerator = referenceGenerator;
        this.paymentMapper = paymentMapper;
        this.clock = clock;
    }

    /**
     * Processes a payment. Deliberately not wrapped in one big transaction: each repository call runs in its own
     * short transaction, so that a unique-constraint collision (concurrent request) can be caught and resolved
     * by re-reading the winning row.
     */
    public PaymentCreationResult createPayment(CreatePaymentRequest request, String idempotencyKey) {
        String fingerprint = RequestFingerprint.of(request);

        var existingByKey = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existingByKey.isPresent()) {
            return replay(existingByKey.get(), fingerprint);
        }

        if (request.method() == PaymentMethod.CARD && !TestCardTokens.isKnown(request.cardToken())) {
            throw new InvalidRequestException("Unknown card token");
        }
        if (paymentRepository.existsByOrderId(request.orderId())) {
            throw new DuplicateResourceException("A payment already exists for order " + request.orderId());
        }

        Payment payment = buildPayment(request, idempotencyKey, fingerprint);
        try {
            Payment saved = paymentRepository.saveAndFlush(payment);
            log.info("Payment {} created for order {}: method={}, status={}", saved.getReference(),
                    saved.getOrderId(), saved.getMethod(), saved.getStatus());
            return new PaymentCreationResult(paymentMapper.toResponse(saved), false);
        } catch (DataIntegrityViolationException e) {
            // concurrent request: the unique constraints decided who won, apply the idempotency rules on the winner
            var winner = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return replay(winner.get(), fingerprint);
            }
            throw new DuplicateResourceException("A payment already exists for order " + request.orderId());
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long id) {
        return paymentMapper.toResponse(paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id)));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByOrder(Long orderId) {
        return paymentMapper.toResponse(paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for order " + orderId)));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(UUID ownerId) {
        List<Payment> payments = ownerId == null
                ? paymentRepository.findAll(Sort.by("id"))
                : paymentRepository.findByOwnerIdOrderByIdAsc(ownerId);
        return payments.stream().map(paymentMapper::toResponse).toList();
    }

    /** Marks a pending cash-on-delivery payment as collected. */
    @Transactional
    public PaymentResponse collect(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        if (payment.getMethod() != PaymentMethod.CASH_ON_DELIVERY || payment.getStatus() != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateException("Payment " + id + " cannot be collected (method="
                    + payment.getMethod() + ", status=" + payment.getStatus() + ")");
        }
        payment.markCollected(clock.instant().truncatedTo(ChronoUnit.MICROS));
        log.info("Payment {} collected for order {}", payment.getReference(), payment.getOrderId());
        return paymentMapper.toResponse(payment);
    }

    private PaymentCreationResult replay(Payment existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new DuplicateResourceException("Idempotency-Key already used with different parameters");
        }
        log.info("Idempotent replay of payment {} (order {})", existing.getReference(), existing.getOrderId());
        return new PaymentCreationResult(paymentMapper.toResponse(existing), true);
    }

    private Payment buildPayment(CreatePaymentRequest request, String idempotencyKey, String fingerprint) {
        String reference = newUniqueReference();
        var amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (request.method() == PaymentMethod.CASH_ON_DELIVERY) {
            return new Payment(reference, request.orderId(), request.ownerId(), amount, request.method(),
                    PaymentStatus.PENDING, null, null, null, idempotencyKey, fingerprint, now);
        }
        CardOutcome outcome = TestCardTokens.isRandom(request.cardToken())
                ? randomizer.draw()
                : TestCardTokens.findFixed(request.cardToken()).orElseThrow(
                        () -> new InvalidRequestException("Unknown card token"));
        return new Payment(reference, request.orderId(), request.ownerId(), amount, request.method(),
                outcome.status(), outcome.failureReason(), outcome.brand(), outcome.last4(), idempotencyKey,
                fingerprint, now);
    }

    private String newUniqueReference() {
        String reference = referenceGenerator.generate();
        while (paymentRepository.existsByReference(reference)) {
            reference = referenceGenerator.generate();
        }
        return reference;
    }
}
