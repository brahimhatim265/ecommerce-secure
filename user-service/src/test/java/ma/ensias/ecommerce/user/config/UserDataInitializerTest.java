package ma.ensias.ecommerce.user.config;

import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.entity.User;
import ma.ensias.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({UserDataInitializer.class, PasswordEncoderConfig.class, ClockConfig.class})
class UserDataInitializerTest {

    private static final List<String> SEEDED_IDS = List.of(
            "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000004",
            "00000000-0000-4000-8000-000000000005", "00000000-0000-4000-8000-000000000006",
            "00000000-0000-4000-8000-000000000007", "00000000-0000-4000-8000-000000000008",
            "00000000-0000-4000-8000-000000000009");

    @Autowired
    private UserDataInitializer initializer;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loadsTheNineDemoAccountsWithTheirFixedUuids() {
        initializer.run(null);

        assertThat(userRepository.count()).isEqualTo(9);
        for (String id : SEEDED_IDS) {
            assertThat(userRepository.findById(UUID.fromString(id))).as("account %s", id).isPresent();
        }
    }

    @Test
    void seedsTheExpectedProfiles() {
        initializer.run(null);

        User hamza = userRepository.findById(UUID.fromString(SEEDED_IDS.get(0))).orElseThrow();
        assertThat(hamza.getEmail()).isEqualTo("hamza.belhaj@example.com");
        assertThat(hamza.getFirstName()).isEqualTo("Hamza");
        assertThat(hamza.getLastName()).isEqualTo("Belhaj");
        assertThat(hamza.getCity()).isEqualTo("Casablanca");
        assertThat(hamza.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(hamza.isActive()).isTrue();

        User admin = userRepository.findById(UUID.fromString(SEEDED_IDS.get(7))).orElseThrow();
        assertThat(admin.getEmail()).isEqualTo("admin@example.com");
        assertThat(admin.getRoles()).containsExactly(Role.ADMIN);

        User anas = userRepository.findById(UUID.fromString(SEEDED_IDS.get(8))).orElseThrow();
        assertThat(anas.getEmail()).isEqualTo("anas.tazi@example.com");
        assertThat(anas.isActive()).isFalse();
    }

    @Test
    void storesPasswordsAsBcryptHashes() {
        initializer.run(null);

        for (User user : userRepository.findAll()) {
            assertThat(user.getPasswordHash()).startsWith("$2");
            assertThat(passwordEncoder.matches("Demo@2026", user.getPasswordHash())).isTrue();
        }
    }

    @Test
    void createdAtValuesAreSpreadOverTheLastWeeks() {
        initializer.run(null);

        assertThat(userRepository.findAll().stream().map(User::getCreatedAt).distinct().count()).isEqualTo(9);
    }

    @Test
    void doesNothingWhenTheTableIsNotEmpty() {
        initializer.run(null);
        initializer.run(null);

        assertThat(userRepository.count()).isEqualTo(9);
    }
}
