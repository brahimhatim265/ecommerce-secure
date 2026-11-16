package ma.ensias.ecommerce.user.repository;

import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private static User user(String email, Set<Role> roles) {
        return new User(UUID.randomUUID(), email, "$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234", "Hamza",
                "Belhaj", roles, Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void findByEmailReturnsThePersistedUserWithItsRoles() {
        userRepository.saveAndFlush(user("hamza.belhaj@example.com", Set.of(Role.CUSTOMER, Role.ADMIN)));

        assertThat(userRepository.findByEmail("hamza.belhaj@example.com")).isPresent()
                .get().extracting(User::getRoles).isEqualTo(Set.of(Role.CUSTOMER, Role.ADMIN));
        assertThat(userRepository.findByEmail("other@example.com")).isEmpty();
    }

    @Test
    void existsByEmailReflectsTheContent() {
        userRepository.saveAndFlush(user("a@example.com", Set.of(Role.CUSTOMER)));

        assertThat(userRepository.existsByEmail("a@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("b@example.com")).isFalse();
    }

    @Test
    void emailMustBeUniqueInTheDatabase() {
        userRepository.saveAndFlush(user("dup@example.com", Set.of(Role.CUSTOMER)));

        assertThatThrownBy(() -> userRepository.saveAndFlush(user("dup@example.com", Set.of(Role.CUSTOMER))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newUsersAreActiveByDefault() {
        User saved = userRepository.saveAndFlush(user("active@example.com", Set.of(Role.CUSTOMER)));

        assertThat(userRepository.findById(saved.getId())).get().extracting(User::isActive).isEqualTo(true);
    }
}
