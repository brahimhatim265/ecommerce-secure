package ma.ensias.ecommerce.auth.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ma.ensias.ecommerce.auth.client.UserServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserServiceAuthenticationProviderLoggingTest {

    private static final String VERIFY_URL = "http://localhost:8081/internal/auth/verify";
    private static final String EMAIL = "hamza.belhaj@example.com";
    private static final String PASSWORD = "Demo@2026";
    private static final String CUSTOMER_JSON = """
            {"id":"00000000-0000-4000-8000-000000000001","email":"hamza.belhaj@example.com",
             "firstName":"Hamza","lastName":"Belhaj","roles":["CUSTOMER"]}""";

    private final Logger providerLogger =
            (Logger) LoggerFactory.getLogger(UserServiceAuthenticationProvider.class);

    private ListAppender<ILoggingEvent> logs;
    private MockRestServiceServer server;
    private UserServiceAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        providerLogger.addAppender(logs);

        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new UserServiceAuthenticationProvider(new UserServiceClient(builder.build()));
    }

    @AfterEach
    void tearDown() {
        providerLogger.detachAppender(logs);
    }

    @Test
    void badCredentialsAreLoggedAtWarnWithMaskedEmailAndReason() {
        failLogin(withStatus(HttpStatus.UNAUTHORIZED), EMAIL, PASSWORD);

        assertSingleSafeWarning("h***@example.com", "Invalid credentials");
    }

    @Test
    void disabledAccountIsLoggedWithItsInternalReason() {
        failLogin(withStatus(HttpStatus.FORBIDDEN), EMAIL, PASSWORD);

        assertSingleSafeWarning("h***@example.com", "Account disabled");
    }

    @Test
    void serviceFailureIsLoggedWithoutTheRawResponse() {
        failLogin(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"secret\":\"raw-response-body\"}").contentType(MediaType.APPLICATION_JSON),
                EMAIL, PASSWORD);

        assertSingleSafeWarning("h***@example.com", "user-service answered HTTP 500");
        assertThat(logs.list.get(0).getFormattedMessage()).doesNotContain("raw-response-body");
    }

    @Test
    void blankPasswordIsLogged() {
        attempt(EMAIL, "");

        assertSingleSafeWarning("h***@example.com", "Invalid credentials");
    }

    @Test
    void successfulLoginLogsNoWarning() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));

        provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(EMAIL, PASSWORD));

        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }

    private void failLogin(ResponseCreator response, String email, String password) {
        server.expect(requestTo(VERIFY_URL)).andRespond(response);
        attempt(email, password);
    }

    private void attempt(String email, String password) {
        try {
            provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        } catch (AuthenticationException expected) {
            // the failure itself is covered by UserServiceAuthenticationProviderTest
        }
    }

    private void assertSingleSafeWarning(String maskedEmail, String reason) {
        List<ILoggingEvent> warnings = logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertThat(warnings).hasSize(1);

        String message = warnings.get(0).getFormattedMessage();
        assertThat(message).contains(maskedEmail).contains(reason);
        assertThat(message).doesNotContain(PASSWORD).doesNotContain("hamza.belhaj").doesNotContain("$2");
    }
}