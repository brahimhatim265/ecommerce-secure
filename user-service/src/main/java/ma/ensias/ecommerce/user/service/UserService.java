package ma.ensias.ecommerce.user.service;

import ma.ensias.ecommerce.user.dto.CreateUserRequest;
import ma.ensias.ecommerce.user.dto.UpdateStatusRequest;
import ma.ensias.ecommerce.user.dto.UpdateUserRequest;
import ma.ensias.ecommerce.user.dto.UserResponse;
import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.entity.User;
import ma.ensias.ecommerce.user.exception.DuplicateResourceException;
import ma.ensias.ecommerce.user.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.user.mapper.UserMapper;
import ma.ensias.ecommerce.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Account management. Emails are normalized before any write or lookup; uniqueness is checked here and
 * guaranteed by a database constraint (violations are translated to 409).
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final Clock clock;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, UserMapper userMapper,
                       Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    /** Registers a new account. The role is always CUSTOMER: roles are not settable through the API. */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String email = EmailUtils.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email already in use");
        }
        Instant now = clock.instant();
        User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(request.password()),
                request.firstName().trim(), request.lastName().trim(), EnumSet.of(Role.CUSTOMER), now);
        user.setContact(request.phone(), request.address(), request.city());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // concurrent registration with the same email: the unique constraint won the race
            throw new DuplicateResourceException("Email already in use");
        }
        log.info("User created: id={}, email={}", user.getId(), EmailUtils.mask(email));
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers(String email) {
        if (email != null) {
            return userRepository.findByEmail(EmailUtils.normalize(email))
                    .map(user -> List.of(userMapper.toResponse(user)))
                    .orElseGet(List::of);
        }
        return userRepository.findAll(Sort.by("createdAt", "id")).stream().map(userMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {
        return userMapper.toResponse(findOrThrow(id));
    }

    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = findOrThrow(id);
        user.updateProfile(request.firstName().trim(), request.lastName().trim(), request.phone(),
                request.address(), request.city(), clock.instant());
        log.info("User profile updated: id={}", id);
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse updateStatus(UUID id, UpdateStatusRequest request) {
        User user = findOrThrow(id);
        user.changeActive(request.active(), clock.instant());
        log.info("User status changed: id={}, active={}", id, request.active());
        return userMapper.toResponse(user);
    }

    private User findOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }
}
