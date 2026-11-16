package ma.ensias.ecommerce.user.repository;

import ma.ensias.ecommerce.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** The email must already be normalized (trimmed, lower-case). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
