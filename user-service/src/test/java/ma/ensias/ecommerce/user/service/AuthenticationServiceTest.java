package ma.ensias.ecommerce.user.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ma.ensias.ecommerce.user.dto.VerifiedIdentityResponse;
import ma.ensias.ecommerce.user.dto.VerifyCredentialsRequest;
import ma.ensias.ecommerce.user.entity.Role;
import ma.ensias.ecommerce.user.entity.User;
import ma.ensias.ecommerce.user.exception.AccountDisabledException;
import ma.ensias.ecommerce.user.exception.InvalidCredentialsException;
import ma.ensias.ecommerce.user.mapper.UserMapper;
import ma.ensias.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final String PASSWORD = "S3cret-Pass";
    private static final String EMAIL = "hamza.belhaj@example.com";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private AuthenticationService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        passwordEncoder = spy(new BCryptPasswordEncoder(4));
        service = new AuthenticationService(userRepository, passwordEncoder, new UserMapper());
        serviceLogger = (Logger) LoggerFactory.getLogger(AuthenticationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    private User user(boolean active) {
        User user = new User(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                EMAIL,
                new BCryptPasswordEncoder(4).encode(PASSWORD),
                "Hamza",
                "Belhaj",
                Set.of(Role.CUSTOMER),
                Instant.now()
        );

        if (!active) {
            user.changeActive(false, Instant.now());
        }

        return user;
    }

    @Test
    void validCredentialsReturnTheVerifiedIdentity() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        VerifiedIdentityResponse identity = service.verify(new VerifyCredentialsRequest(EMAIL, PASSWORD));

        assertThat(identity.id()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000001"));
        assertThat(identity.email()).isEqualTo(EMAIL);
        assertThat(identity.firstName()).isEqualTo("Hamza");
        assertThat(identity.lastName()).isEqualTo("Belhaj");
        assertThat(identity.roles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void emailIsMatchedIgnoringCaseAndSurroundingSpaces() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        VerifiedIdentityResponse identity = service.verify(new VerifyCredentialsRequest("  HAMZA.Belhaj@Example.com ", PASSWORD));

        assertThat(identity.email()).isEqualTo(EMAIL);
    }

    @Test
    void wrongPasswordIsRejectedWithInvalidCredentials() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> service.verify(new VerifyCredentialsRequest(EMAIL, "wrong-password1")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void unknownEmailGivesTheSameMessageAndStillPerformsADummyBcryptComparison() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(new VerifyCredentialsRequest("nobody@example.com", "whatever123")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");
        verify(passwordEncoder).matches(eq("whatever123"), anyString());
    }

    @Test
    void unknownAccountAndWrongPasswordProduceExactlyTheSameMessage() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        String wrongPassword = captureMessage(new VerifyCredentialsRequest(EMAIL, "wrong-password1"));
        String unknownAccount = captureMessage(new VerifyCredentialsRequest("nobody@example.com", "wrong-password1"));

        assertThat(wrongPassword).isEqualTo(unknownAccount);
    }

    private String captureMessage(VerifyCredentialsRequest request) {
        try {
            service.verify(request);
            return "no exception";
        } catch (InvalidCredentialsException e) {
            return e.getMessage();
        }
    }

    @Test
    void disabledAccountWithCorrectPasswordIsForbidden() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> service.verify(new VerifyCredentialsRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AccountDisabledException.class)
                .hasMessage("Account is disabled");
    }

    @Test
    void disabledAccountWithWrongPasswordIsReportedAsInvalidCredentials() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> service.verify(new VerifyCredentialsRequest(EMAIL, "wrong-password1")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void veryLongPasswordIsTreatedAsAMismatchNotAServerError() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> service.verify(new VerifyCredentialsRequest(EMAIL, "x".repeat(150))))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void failuresAreLoggedWithAMaskedEmailAndNeverWithThePassword() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true)));

        assertThatThrownBy(() -> service.verify(new VerifyCredentialsRequest(EMAIL, "Leaked-Pass-123")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain("Leaked-Pass-123").doesNotContain(PASSWORD)
                    .doesNotContain("$2").doesNotContain(EMAIL);
        }
        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("h***@example.com");
    }
}
