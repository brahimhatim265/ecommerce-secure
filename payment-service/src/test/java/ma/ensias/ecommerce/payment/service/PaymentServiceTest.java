package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.dto.CreatePaymentRequest;
import ma.ensias.ecommerce.payment.dto.PaymentCreationResult;
import ma.ensias.ecommerce.payment.dto.PaymentResponse;
import ma.ensias.ecommerce.payment.entity.FailureReason;
import ma.ensias.ecommerce.payment.entity.Payment;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import ma.ensias.ecommerce.payment.exception.DuplicateResourceException;
import ma.ensias.ecommerce.payment.exception.InvalidPaymentStateException;
import ma.ensias.ecommerce.payment.exception.InvalidRequestException;
import ma.ensias.ecommerce.payment.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.payment.mapper.PaymentMapper;
import ma.ensias.ecommerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String KEY = "order-42";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentOutcomeRandomizer randomizer;
    @Mock
    private PaymentReferenceGenerator referenceGenerator;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, randomizer, referenceGenerator, new PaymentMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreatePaymentRequest card(String token) {
        return new CreatePaymentRequest(42L, OWNER, new BigDecimal("6780.00"), "MAD", PaymentMethod.CARD, token);
    }

    private static CreatePaymentRequest cashOnDelivery() {
        return new CreatePaymentRequest(42L, OWNER, new BigDecimal("6780.00"), "MAD",
                PaymentMethod.CASH_ON_DELIVERY, null);
    }

    /** Stubs the repository for a brand-new payment (no replay, order not yet paid, free reference). */
    private void stubNewPayment() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderId(42L)).thenReturn(false);
        when(referenceGenerator.generate()).thenReturn("PAY-ABCDE12345");
        when(paymentRepository.existsByReference("PAY-ABCDE12345")).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Payment captureSaved() {
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void approvedVisaTokenSucceeds() {
        stubNewPayment();

        PaymentCreationResult result = service.createPayment(card("tok_visa_approved"), KEY);

        assertThat(result.replayed()).isFalse();
        PaymentResponse payment = result.payment();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.cardBrand()).isEqualTo("VISA");
        assertThat(payment.cardLast4()).isEqualTo("4242");
        assertThat(payment.failureReason()).isNull();
        assertThat(payment.reference()).isEqualTo("PAY-ABCDE12345");
        assertThat(payment.currency()).isEqualTo("MAD");
        assertThat(payment.amount()).isEqualByComparingTo("6780.00");
        assertThat(payment.createdAt()).isEqualTo(NOW);
        verifyNoInteractions(randomizer);
    }

    @Test
    void mastercardTokenSucceeds() {
        stubNewPayment();

        PaymentResponse payment = service.createPayment(card("tok_mastercard_approved"), KEY).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.cardBrand()).isEqualTo("MASTERCARD");
        assertThat(payment.cardLast4()).isEqualTo("4444");
    }

    @Test
    void insufficientFundsTokenIsAFailedPaymentNotAnException() {
        stubNewPayment();

        PaymentResponse payment = service.createPayment(card("tok_declined_insufficient_funds"), KEY).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(payment.cardLast4()).isEqualTo("9995");
    }

    @Test
    void expiredCardTokenFails() {
        stubNewPayment();

        PaymentResponse payment = service.createPayment(card("tok_declined_expired_card"), KEY).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureReason()).isEqualTo(FailureReason.EXPIRED_CARD);
        assertThat(payment.cardLast4()).isEqualTo("0069");
    }

    @Test
    void genericDeclineTokenFails() {
        stubNewPayment();

        PaymentResponse payment = service.createPayment(card("tok_declined_generic"), KEY).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureReason()).isEqualTo(FailureReason.CARD_DECLINED);
        assertThat(payment.cardLast4()).isEqualTo("0002");
    }

    @Test
    void unknownTokenIsRefusedBeforeAnythingIsSaved() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPayment(card("tok_nope"), KEY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Unknown card token");
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void cashOnDeliveryIsPendingWithoutCardDetails() {
        stubNewPayment();

        PaymentResponse payment = service.createPayment(cashOnDelivery(), KEY).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.method()).isEqualTo(PaymentMethod.CASH_ON_DELIVERY);
        assertThat(payment.cardBrand()).isNull();
        assertThat(payment.cardLast4()).isNull();
        assertThat(payment.failureReason()).isNull();
        assertThat(payment.collectedAt()).isNull();
    }

    @Test
    void theCardTokenIsNeverStored() {
        stubNewPayment();

        service.createPayment(card("tok_visa_approved"), KEY);

        Payment saved = captureSaved();
        assertThat(saved.toString()).doesNotContain("tok_visa_approved");
        assertThat(saved.getRequestFingerprint()).doesNotContain("tok_visa_approved").hasSize(64);
        assertThat(saved.getIdempotencyKey()).isEqualTo(KEY);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCEEDED", "FAILED"})
    void randomTokenUsesTheDrawnOutcomeExactlyOnce(PaymentStatus drawnStatus) {
        stubNewPayment();
        CardOutcome drawn = drawnStatus == PaymentStatus.SUCCEEDED
                ? TestCardTokens.MASTERCARD_APPROVED : TestCardTokens.EXPIRED_CARD;
        when(randomizer.draw()).thenReturn(drawn);

        PaymentResponse payment = service.createPayment(card("tok_random"), KEY).payment();

        assertThat(payment.status()).isEqualTo(drawnStatus);
        assertThat(payment.cardBrand()).isEqualTo(drawn.brand());
        assertThat(payment.cardLast4()).isEqualTo(drawn.last4());
        assertThat(payment.failureReason()).isEqualTo(drawn.failureReason());
        verify(randomizer).draw();
    }

    @Test
    void everyPossibleRandomOutcomeIsProducedCorrectly() {
        CardOutcome[] outcomes = {TestCardTokens.VISA_APPROVED, TestCardTokens.MASTERCARD_APPROVED,
                TestCardTokens.INSUFFICIENT_FUNDS, TestCardTokens.CARD_DECLINED, TestCardTokens.EXPIRED_CARD};
        for (CardOutcome outcome : outcomes) {
            org.mockito.Mockito.reset(paymentRepository, randomizer, referenceGenerator);
            stubNewPayment();
            when(randomizer.draw()).thenReturn(outcome);

            PaymentResponse payment = service.createPayment(card("tok_random"), KEY).payment();

            assertThat(payment.status()).isEqualTo(outcome.status());
            assertThat(payment.failureReason()).isEqualTo(outcome.failureReason());
        }
    }

    @Test
    void replayWithSameKeyAndParametersReturnsTheStoredPaymentWithoutNewDrawOrRow() {
        Payment stored = new Payment("PAY-ABCDE12345", 42L, OWNER, new BigDecimal("6780.00"), PaymentMethod.CARD,
                PaymentStatus.FAILED, FailureReason.EXPIRED_CARD, "VISA", "0069", KEY,
                RequestFingerprint.of(card("tok_random")), NOW);
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(stored));

        PaymentCreationResult result = service.createPayment(card("tok_random"), KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().reference()).isEqualTo("PAY-ABCDE12345");
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(randomizer);
    }

    @Test
    void sameKeyWithDifferentParametersIsAConflict() {
        Payment stored = new Payment("PAY-ABCDE12345", 42L, OWNER, new BigDecimal("6780.00"), PaymentMethod.CARD,
                PaymentStatus.SUCCEEDED, null, "VISA", "4242", KEY,
                RequestFingerprint.of(card("tok_visa_approved")), NOW);
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(stored));
        CreatePaymentRequest other = new CreatePaymentRequest(42L, OWNER, new BigDecimal("1.00"), "MAD",
                PaymentMethod.CARD, "tok_visa_approved");

        assertThatThrownBy(() -> service.createPayment(other, KEY))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("different parameters");
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void differentKeyForAnAlreadyPaidOrderIsAConflict() {
        when(paymentRepository.findByIdempotencyKey("other-key")).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderId(42L)).thenReturn(true);

        assertThatThrownBy(() -> service.createPayment(card("tok_visa_approved"), "other-key"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("order 42");
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(randomizer);
    }

    @Test
    void concurrentCollisionOnTheSameKeyIsResolvedAsAReplay() {
        Payment winner = new Payment("PAY-WINNER0001", 42L, OWNER, new BigDecimal("6780.00"), PaymentMethod.CARD,
                PaymentStatus.SUCCEEDED, null, "VISA", "4242", KEY,
                RequestFingerprint.of(card("tok_visa_approved")), NOW);
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(paymentRepository.existsByOrderId(42L)).thenReturn(false);
        when(referenceGenerator.generate()).thenReturn("PAY-ABCDE12345");
        when(paymentRepository.existsByReference(anyString())).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(new DataIntegrityViolationException("dup"));

        PaymentCreationResult result = service.createPayment(card("tok_visa_approved"), KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().reference()).isEqualTo("PAY-WINNER0001");
    }

    @Test
    void concurrentCollisionOnTheOrderWithAnotherKeyIsAConflict() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.empty());
        when(paymentRepository.existsByOrderId(42L)).thenReturn(false);
        when(referenceGenerator.generate()).thenReturn("PAY-ABCDE12345");
        when(paymentRepository.existsByReference(anyString())).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.createPayment(card("tok_visa_approved"), KEY))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void referenceCollisionTriggersANewReference() {
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderId(42L)).thenReturn(false);
        when(referenceGenerator.generate()).thenReturn("PAY-TAKEN00001", "PAY-FREE000002");
        when(paymentRepository.existsByReference("PAY-TAKEN00001")).thenReturn(true);
        when(paymentRepository.existsByReference("PAY-FREE000002")).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse payment = service.createPayment(card("tok_visa_approved"), KEY).payment();

        assertThat(payment.reference()).isEqualTo("PAY-FREE000002");
    }

    // ---------------------------------------------------------------- collect

    private Payment pending(PaymentMethod method, PaymentStatus status) {
        return new Payment("PAY-ABCDE12345", 42L, OWNER, new BigDecimal("6780.00"), method, status, null,
                null, null, KEY, "fp", NOW.minusSeconds(60));
    }

    @Test
    void collectTurnsAPendingCashPaymentIntoSucceeded() {
        Payment payment = pending(PaymentMethod.CASH_ON_DELIVERY, PaymentStatus.PENDING);
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));

        PaymentResponse response = service.collect(7L);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.collectedAt()).isEqualTo(NOW);
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void collectingASecondTimeIsAConflict() {
        Payment payment = pending(PaymentMethod.CASH_ON_DELIVERY, PaymentStatus.PENDING);
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));
        service.collect(7L);

        assertThatThrownBy(() -> service.collect(7L)).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void collectingACardPaymentIsAConflict() {
        when(paymentRepository.findById(7L))
                .thenReturn(Optional.of(pending(PaymentMethod.CARD, PaymentStatus.SUCCEEDED)));

        assertThatThrownBy(() -> service.collect(7L)).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void collectingAnUnknownPaymentIsNotFound() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collect(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- reads

    @Test
    void getPaymentAndGetByOrderReturnTheStoredPayment() {
        Payment payment = pending(PaymentMethod.CASH_ON_DELIVERY, PaymentStatus.PENDING);
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(payment));

        assertThat(service.getPayment(7L).reference()).isEqualTo("PAY-ABCDE12345");
        assertThat(service.getPaymentByOrder(42L).orderId()).isEqualTo(42L);
    }

    @Test
    void unknownPaymentOrOrderIsNotFound() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.empty());
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPayment(1L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getPaymentByOrder(1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listFiltersByOwnerWhenProvided() {
        when(paymentRepository.findByOwnerIdOrderByIdAsc(OWNER))
                .thenReturn(java.util.List.of(pending(PaymentMethod.CARD, PaymentStatus.SUCCEEDED)));

        assertThat(service.listPayments(OWNER)).hasSize(1);
    }
}
