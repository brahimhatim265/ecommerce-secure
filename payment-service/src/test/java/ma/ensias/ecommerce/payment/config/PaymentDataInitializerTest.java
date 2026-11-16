package ma.ensias.ecommerce.payment.config;

import ma.ensias.ecommerce.payment.entity.Payment;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import ma.ensias.ecommerce.payment.repository.PaymentRepository;
import ma.ensias.ecommerce.payment.service.PaymentReferenceGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({PaymentDataInitializer.class, PaymentReferenceGenerator.class, ClockConfig.class})
class PaymentDataInitializerTest {

    @Autowired
    private PaymentDataInitializer initializer;

    @Autowired
    private PaymentRepository repository;

    @Test
    void seedsOnePaymentPerDemoOrder() {
        initializer.run(null);

        List<Payment> payments = repository.findAll();
        assertThat(payments).hasSize(5);
        assertThat(payments).extracting(Payment::getOrderId).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(payments).extracting(Payment::getIdempotencyKey)
                .containsExactlyInAnyOrder("order-1", "order-2", "order-3", "order-4", "order-5");
        assertThat(payments).allSatisfy(p -> {
            assertThat(p.getCurrency()).isEqualTo("MAD");
            assertThat(p.getReference()).matches("PAY-[A-Z0-9]{10}");
        });
    }

    @Test
    void seedsTheExpectedMethodsStatusesAndCards() {
        initializer.run(null);

        Payment first = repository.findByOrderId(1L).orElseThrow();
        assertThat(first.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(first.getCardBrand()).isEqualTo("VISA");
        assertThat(first.getCardLast4()).isEqualTo("4242");
        assertThat(first.getAmount()).isEqualByComparingTo(new BigDecimal("6780.00"));
        assertThat(first.getOwnerId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000001"));

        Payment second = repository.findByOrderId(2L).orElseThrow();
        assertThat(second.getCardBrand()).isEqualTo("MASTERCARD");
        assertThat(second.getCardLast4()).isEqualTo("4444");
        assertThat(second.getAmount()).isEqualByComparingTo(new BigDecimal("13990.00"));

        assertThat(repository.findByOrderId(3L).orElseThrow().getAmount()).isEqualByComparingTo("5490.00");
        assertThat(repository.findByOrderId(4L).orElseThrow().getAmount()).isEqualByComparingTo("3539.00");

        Payment cash = repository.findByOrderId(5L).orElseThrow();
        assertThat(cash.getMethod()).isEqualTo(PaymentMethod.CASH_ON_DELIVERY);
        assertThat(cash.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(cash.getCardBrand()).isNull();
        assertThat(cash.getCardLast4()).isNull();
        assertThat(cash.getAmount()).isEqualByComparingTo("2988.00");
    }

    @Test
    void isIdempotentWhenTheTableIsNotEmpty() {
        initializer.run(null);
        initializer.run(null);

        assertThat(repository.count()).isEqualTo(5);
    }
}
