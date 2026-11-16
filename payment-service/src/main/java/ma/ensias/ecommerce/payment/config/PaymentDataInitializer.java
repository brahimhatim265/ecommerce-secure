package ma.ensias.ecommerce.payment.config;

import ma.ensias.ecommerce.payment.entity.Payment;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import ma.ensias.ecommerce.payment.repository.PaymentRepository;
import ma.ensias.ecommerce.payment.service.CardOutcome;
import ma.ensias.ecommerce.payment.service.PaymentReferenceGenerator;
import ma.ensias.ecommerce.payment.service.TestCardTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads one payment per demo order (orders 1 to 5 of order-service) when the table is empty.
 * Timestamps use the same day-based offsets as the order seed so that both services stay coherent.
 */
@Component
public class PaymentDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaymentDataInitializer.class);

    /** Delay between an order creation and its payment. */
    private static final long PAYMENT_DELAY_SECONDS = 30;

    private final PaymentRepository paymentRepository;
    private final PaymentReferenceGenerator referenceGenerator;
    private final Clock clock;

    public PaymentDataInitializer(PaymentRepository paymentRepository, PaymentReferenceGenerator referenceGenerator,
                                  Clock clock) {
        this.paymentRepository = paymentRepository;
        this.referenceGenerator = referenceGenerator;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (paymentRepository.count() > 0) {
            return;
        }
        Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
        List<Payment> payments = new ArrayList<>();

        payments.add(card(1, "00000000-0000-4000-8000-000000000001", "6780.00", TestCardTokens.VISA_APPROVED,
                orderCreatedAt(today, 1)));
        payments.add(card(2, "00000000-0000-4000-8000-000000000002", "13990.00",
                TestCardTokens.MASTERCARD_APPROVED, orderCreatedAt(today, 2)));
        payments.add(card(3, "00000000-0000-4000-8000-000000000003", "5490.00", TestCardTokens.VISA_APPROVED,
                orderCreatedAt(today, 3)));
        payments.add(card(4, "00000000-0000-4000-8000-000000000004", "3539.00",
                TestCardTokens.MASTERCARD_APPROVED, orderCreatedAt(today, 4)));
        payments.add(new Payment(referenceGenerator.generate(), 5L,
                UUID.fromString("00000000-0000-4000-8000-000000000005"), new BigDecimal("2988.00"),
                PaymentMethod.CASH_ON_DELIVERY, PaymentStatus.PENDING, null, null, null, "order-5",
                "seed-order-5", orderCreatedAt(today, 5).plusSeconds(PAYMENT_DELAY_SECONDS)));

        paymentRepository.saveAll(payments);
        log.info("Seeded {} demo payments", payments.size());
    }

    private Payment card(long orderId, String ownerId, String amount, CardOutcome outcome, Instant orderCreatedAt) {
        return new Payment(referenceGenerator.generate(), orderId, UUID.fromString(ownerId), new BigDecimal(amount),
                PaymentMethod.CARD, outcome.status(), outcome.failureReason(), outcome.brand(), outcome.last4(),
                "order-" + orderId, "seed-order-" + orderId, orderCreatedAt.plusSeconds(PAYMENT_DELAY_SECONDS));
    }

    /** Same formula as the order-service seed: order N is created (6 - N) days before today, at a fixed time. */
    static Instant orderCreatedAt(Instant today, int orderNumber) {
        return today.minus(6 - orderNumber, ChronoUnit.DAYS).plus(9 + orderNumber, ChronoUnit.HOURS)
                .plus(orderNumber * 7L, ChronoUnit.MINUTES);
    }
}
