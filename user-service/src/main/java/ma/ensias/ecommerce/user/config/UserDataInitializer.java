package ma.ensias.ecommerce.user.config;

import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.entity.User;
import ma.ensias.ecommerce.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the demo accounts when the table is empty. All accounts share one demonstration password
 * ({@code app.seed.default-password}, default {@code Demo@2026}) which must never be used outside a demo.
 */
@Component
public class UserDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserDataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String defaultPassword;

    public UserDataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
                               @Value("${app.seed.default-password}") String defaultPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.defaultPassword = defaultPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        String hash = passwordEncoder.encode(defaultPassword);
        Instant now = clock.instant();
        List<User> users = new ArrayList<>();

        users.add(seed("00000000-0000-4000-8000-000000000001", "Hamza", "Belhaj", "hamza.belhaj@example.com",
                "Casablanca", "45 Rue Ibnou Katir, Maârif", "+212 600 11 22 01", Role.CUSTOMER, true,
                hash, now.minus(30, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000002", "Issam", "Bourhim", "issam.bourhim@example.com",
                "Rabat", "12 Rue Oum Rabia, Agdal", "+212 600 11 22 02", Role.CUSTOMER, true,
                hash, now.minus(27, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000003", "Nizar", "Beniad", "nizar.beniad@example.com",
                "Marrakech", "8 Avenue Mohammed VI, Guéliz", "+212 600 11 22 03", Role.CUSTOMER, true,
                hash, now.minus(24, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000004", "Saad", "Hada", "saad.hada@example.com",
                "Tanger", "27 Rue de la Liberté, Centre-ville", "+212 600 11 22 04", Role.CUSTOMER, true,
                hash, now.minus(21, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000005", "Mehdi", "Fadli", "mehdi.fadli@example.com",
                "Fès", "5 Rue Atlas, Ville Nouvelle", "+212 600 11 22 05", Role.CUSTOMER, true,
                hash, now.minus(18, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000006", "Brahim", "Hatim", "brahim.hatim@example.com",
                "Agadir", "33 Avenue des FAR, Centre", "+212 600 11 22 06", Role.CUSTOMER, true,
                hash, now.minus(14, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000007", "Louai", "Idrissi", "louai.idrissi@example.com",
                "Salé", "14 Rue Tarik Ibn Ziyad, Hay Salam", "+212 600 11 22 07", Role.CUSTOMER, true,
                hash, now.minus(10, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000008", "Administrateur", "Boutique", "admin@example.com",
                "Casablanca", "Boulevard Zerktouni, Siège", "+212 522 00 11 22", Role.ADMIN, true,
                hash, now.minus(35, ChronoUnit.DAYS)));
        users.add(seed("00000000-0000-4000-8000-000000000009", "Anas", "Tazi", "anas.tazi@example.com",
                "Oujda", "21 Rue Moulay Ismail", "+212 600 11 22 09", Role.CUSTOMER, false,
                hash, now.minus(7, ChronoUnit.DAYS)));

        userRepository.saveAll(users);
        log.info("Seeded {} demo users", users.size());
    }

    private User seed(String id, String firstName, String lastName, String email, String city, String address,
                      String phone, Role role, boolean active, String passwordHash, Instant createdAt) {
        User user = new User(UUID.fromString(id), email, passwordHash, firstName, lastName, Set.of(role),
                createdAt);
        user.setContact(phone, address, city);
        if (!active) {
            user.changeActive(false, createdAt);
        }
        return user;
    }
}
