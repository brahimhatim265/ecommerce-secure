package ma.ensias.ecommerce.payment.repository;

import ma.ensias.ecommerce.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    boolean existsByReference(String reference);

    List<Payment> findByOwnerIdOrderByIdAsc(UUID ownerId);
}
