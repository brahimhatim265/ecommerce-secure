package ma.ensias.ecommerce.payment.repository;

import ma.ensias.ecommerce.payment.entity.Payment;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PaymentRepositoryTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Autowired
    private PaymentRepository repository;

    private static Payment payment(String reference, long orderId, UUID owner, String key) {
        return new Payment(reference, orderId, owner, new BigDecimal("100.00"), PaymentMethod.CARD,
                PaymentStatus.SUCCEEDED, null, "VISA", "4242", key, "fp", Instant.parse("2026-11-14T10:00:00Z"));
    }

    @Test
    void customFinders() {
        repository.saveAndFlush(payment("PAY-AAAAAAAAAA", 1, OWNER, "order-1"));
        repository.saveAndFlush(payment("PAY-BBBBBBBBBB", 2, OTHER_OWNER, "order-2"));
        repository.saveAndFlush(payment("PAY-CCCCCCCCCC", 3, OWNER, "order-3"));

        assertThat(repository.findByIdempotencyKey("order-2")).get().extracting(Payment::getOrderId).isEqualTo(2L);
        assertThat(repository.findByOrderId(3L)).get().extracting(Payment::getReference).isEqualTo("PAY-CCCCCCCCCC");
        assertThat(repository.existsByOrderId(1L)).isTrue();
        assertThat(repository.existsByOrderId(99L)).isFalse();
        assertThat(repository.existsByReference("PAY-AAAAAAAAAA")).isTrue();
        assertThat(repository.findByOwnerIdOrderByIdAsc(OWNER)).extracting(Payment::getOrderId)
                .containsExactly(1L, 3L);
    }

    @Test
    void orderIdMustBeUnique() {
        repository.saveAndFlush(payment("PAY-AAAAAAAAAA", 1, OWNER, "order-1"));

        assertThatThrownBy(() -> repository.saveAndFlush(payment("PAY-BBBBBBBBBB", 1, OWNER, "another-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyMustBeUnique() {
        repository.saveAndFlush(payment("PAY-AAAAAAAAAA", 1, OWNER, "same-key"));

        assertThatThrownBy(() -> repository.saveAndFlush(payment("PAY-BBBBBBBBBB", 2, OWNER, "same-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void referenceMustBeUnique() {
        repository.saveAndFlush(payment("PAY-AAAAAAAAAA", 1, OWNER, "order-1"));

        assertThatThrownBy(() -> repository.saveAndFlush(payment("PAY-AAAAAAAAAA", 2, OWNER, "order-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void amountKeepsTwoDecimals() {
        Payment saved = repository.saveAndFlush(payment("PAY-AAAAAAAAAA", 1, OWNER, "order-1"));

        assertThat(repository.findById(saved.getId())).get().extracting(Payment::getAmount)
                .isEqualTo(new BigDecimal("100.00"));
    }
}
