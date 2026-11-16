package ma.ensias.ecommerce.user.service;

import ma.ensias.ecommerce.user.dto.VerifiedIdentityResponse;
import ma.ensias.ecommerce.user.dto.VerifyCredentialsRequest;
import ma.ensias.ecommerce.user.entity.User;
import ma.ensias.ecommerce.user.exception.AccountDisabledException;
import ma.ensias.ecommerce.user.exception.InvalidCredentialsException;
import ma.ensias.ecommerce.user.mapper.UserMapper;
import ma.ensias.ecommerce.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Verifies an email / password pair on behalf of the future authorization server.
 * An unknown account triggers a dummy BCrypt comparison so that response time does not reveal whether the
 * account exists, and both failure causes produce the same message.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final String dummyHash;

    public AuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                 UserMapper userMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        // valid BCrypt hash generated at startup, used only to equalize timing for unknown accounts
        this.dummyHash = passwordEncoder.encode("dummy-" + UUID.randomUUID());
    }

    /**
     * Not transactional on purpose: the single repository read is short and the (slow) BCrypt comparison
     * must not hold a database connection.
     */
    public VerifiedIdentityResponse verify(VerifyCredentialsRequest request) {
        String email = EmailUtils.normalize(request.email());
        Optional<User> found = userRepository.findByEmail(email);

        String hashToCompare = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = matches(request.password(), hashToCompare);

        if (found.isEmpty() || !passwordMatches) {
            log.warn("Authentication failed for {}: {}", EmailUtils.mask(email),
                    found.isEmpty() ? "unknown account" : "wrong password");
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        if (!user.isActive()) {
            log.warn("Authentication refused for {}: account disabled", EmailUtils.mask(email));
            throw new AccountDisabledException();
        }
        log.info("Credentials verified for user id={}", user.getId());
        return userMapper.toIdentity(user);
    }

    private boolean matches(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException e) {
            // e.g. a password that BCrypt refuses to process: treat it as a mismatch, never as a server error
            return false;
        }
    }
}
