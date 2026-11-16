package ma.ensias.ecommerce.order.repository;

import ma.ensias.ecommerce.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByOwnerIdOrderByIdAsc(UUID ownerId);
}
