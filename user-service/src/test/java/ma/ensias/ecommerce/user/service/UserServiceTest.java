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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, passwordEncoder, new UserMapper(), clock);
    }

    private static CreateUserRequest registration(String email) {
        return new CreateUserRequest(email, "Secret123", "Hamza", "Belhaj", "+212 600 11 22 01",
                "45 Rue Ibnou Katir, Maârif", "Casablanca");
    }

    private User existingUser(UUID id, String email) {
        User user = new User(id, email, passwordEncoder.encode("Secret123"), "Hamza", "Belhaj",
                Set.of(Role.CUSTOMER), NOW.minusSeconds(3600));
        user.setContact("+212 600 11 22 01", "45 Rue Ibnou Katir, Maârif", "Casablanca");
        return user;
    }

    @Test
    void createUserNormalizesEmailHashesPasswordAndAssignsCustomerRole() {
        when(userRepository.existsByEmail("hamza.belhaj@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.createUser(registration("  Hamza.Belhaj@Example.com "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("hamza.belhaj@example.com");
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("Secret123");
        assertThat(passwordEncoder.matches("Secret123", saved.getPasswordHash())).isTrue();
        assertThat(saved.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(response.id()).isEqualTo(saved.getId());
        assertThat(response.email()).isEqualTo("hamza.belhaj@example.com");
        assertThat(response.roles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("hamza.belhaj@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(registration("Hamza.Belhaj@Example.com")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void createUserTranslatesUniqueConstraintRaceIntoConflict() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.createUser(registration("hamza.belhaj@example.com")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void twoUsersWithTheSameNamesButDifferentEmailsAreAccepted() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse first = service.createUser(registration("first@example.com"));
        UserResponse second = service.createUser(registration("second@example.com"));

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.firstName()).isEqualTo(second.firstName());
        assertThat(first.lastName()).isEqualTo(second.lastName());
    }

    @Test
    void listUsersByEmailNormalizesTheSearch() {
        User user = existingUser(UUID.randomUUID(), "hamza.belhaj@example.com");
        when(userRepository.findByEmail("hamza.belhaj@example.com")).thenReturn(Optional.of(user));

        List<UserResponse> result = service.listUsers("  Hamza.BELHAJ@example.com");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("hamza.belhaj@example.com");
    }

    @Test
    void listUsersByUnknownEmailReturnsEmptyList() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.listUsers("nobody@example.com")).isEmpty();
    }

    @Test
    void listUsersWithoutFilterReturnsEveryone() {
        User first = existingUser(UUID.randomUUID(), "a@example.com");
        User second = existingUser(UUID.randomUUID(), "b@example.com");
        when(userRepository.findAll(any(Sort.class))).thenReturn(List.of(first, second));

        assertThat(service.listUsers(null)).hasSize(2);
    }

    @Test
    void getUserReturnsTheUser() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(existingUser(id, "a@example.com")));

        assertThat(service.getUser(id).id()).isEqualTo(id);
    }

    @Test
    void getUserFailsWhenUnknown() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateUserChangesProfileButNeverTheEmail() {
        UUID id = UUID.randomUUID();
        User user = existingUser(id, "hamza.belhaj@example.com");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        UserResponse response = service.updateUser(id,
                new UpdateUserRequest(" Hamid ", "Belhaj", null, "1 Rue Test", "Rabat"));

        assertThat(response.firstName()).isEqualTo("Hamid");
        assertThat(response.phone()).isNull();
        assertThat(response.city()).isEqualTo("Rabat");
        assertThat(response.email()).isEqualTo("hamza.belhaj@example.com");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getId()).isEqualTo(id);
    }

    @Test
    void updateUserFailsWhenUnknown() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUser(id, new UpdateUserRequest("A", "B", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStatusDeactivatesAndReactivates() {
        UUID id = UUID.randomUUID();
        User user = existingUser(id, "a@example.com");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        assertThat(service.updateStatus(id, new UpdateStatusRequest(false)).active()).isFalse();
        assertThat(service.updateStatus(id, new UpdateStatusRequest(true)).active()).isTrue();
    }

    @Test
    void updateStatusFailsWhenUnknown() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(id, new UpdateStatusRequest(false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
