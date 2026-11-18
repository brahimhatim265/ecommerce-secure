package ma.ensias.ecommerce.auth.security;

import ma.ensias.ecommerce.auth.client.UserServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserServiceAuthenticationProviderTest {

    private static final String VERIFY_URL = "http://localhost:8081/internal/auth/verify";
    private static final String EMAIL = "hamza.belhaj@example.com";
    private static final String PASSWORD = "Demo@2026";
    private static final String CUSTOMER_JSON = """
            {"id":"00000000-0000-4000-8000-000000000001","email":"hamza.belhaj@example.com",
             "firstName":"Hamza","lastName":"Belhaj","roles":["CUSTOMER"]}""";
    private static final String ADMIN_JSON = """
            {"id":"00000000-0000-4000-8000-000000000009","email":"admin@example.com",
             "firstName":"Admin","lastName":"Root","roles":["ADMIN"]}""";

    private MockRestServiceServer server;
    private UserServiceAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new UserServiceAuthenticationProvider(new UserServiceClient(builder.build()));
    }

    private static Authentication loginAttempt(String email, String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(email, password);
    }

    @Test
    void successBuildsAnAuthenticatedTokenWithTheUserPrincipal() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));
        UsernamePasswordAuthenticationToken attempt =
                UsernamePasswordAuthenticationToken.unauthenticated(EMAIL, PASSWORD);
        attempt.setDetails("request-details");

        Authentication result = provider.authenticate(attempt);

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo("00000000-0000-4000-8000-000000000001");
        assertThat(result.getDetails()).isEqualTo("request-details");
        assertThat(result.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser user = (AuthenticatedUser) result.getPrincipal();
        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.firstName()).isEqualTo("Hamza");
        assertThat(user.lastName()).isEqualTo("Belhaj");
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
        server.verify();
    }

    @Test
    void credentialsAreErasedAfterSuccess() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));

        Authentication result = provider.authenticate(loginAttempt(EMAIL, PASSWORD));

        assertThat(result.getCredentials()).isNull();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void adminRoleBecomesRoleAdmin() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withSuccess(ADMIN_JSON, MediaType.APPLICATION_JSON));

        Authentication result = provider.authenticate(loginAttempt("admin@example.com", PASSWORD));

        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void status401IsBadCredentials() {
        assertThat(failureFor(withStatus(HttpStatus.UNAUTHORIZED))).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void status403IsDisabled() {
        assertThat(failureFor(withStatus(HttpStatus.FORBIDDEN))).isInstanceOf(DisabledException.class);
    }

    @Test
    void status500IsServiceFailure() {
        assertThat(failureFor(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void messageForBadCredentialsIsGeneric() {
        assertThat(failureFor(withStatus(HttpStatus.UNAUTHORIZED)).getMessage())
                .isEqualTo("Invalid credentials");
    }

    @Test
    void messageForDisabledAccountDoesNotRevealIt() {
        assertThat(failureFor(withStatus(HttpStatus.FORBIDDEN)).getMessage())
                .isEqualTo("Login failed");
    }

    @Test
    void messageForServiceFailureDoesNotRevealTheInternalReason() {
        assertThat(failureFor(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)).getMessage())
                .isEqualTo("Login failed");
    }

    @Test
    void timeoutIsServiceFailure() {
        assertThat(failureFor(request -> {
            throw new SocketTimeoutException("Read timed out");
        })).isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void unreachableServiceIsServiceFailure() {
        assertThat(failureFor(request -> {
            throw new ConnectException("Connection refused");
        })).isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void invalidJsonIsServiceFailure() {
        assertThat(failureFor(withSuccess("not json", MediaType.APPLICATION_JSON)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankPasswordIsRejectedWithoutCallingUserService(String password) {
        // no expectation registered: any HTTP call would fail the test
        assertThat(attemptFailure(loginAttempt(EMAIL, password))).isInstanceOf(BadCredentialsException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankEmailIsRejectedWithoutCallingUserService(String email) {
        assertThat(attemptFailure(loginAttempt(email, PASSWORD))).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void supportsOnlyUsernamePasswordTokens() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }

    /** Runs a failing login, checks that nothing in the exception chain leaks the password, returns the exception. */
    private AuthenticationException failureFor(ResponseCreator response) {
        server.expect(requestTo(VERIFY_URL)).andRespond(response);
        return attemptFailure(loginAttempt(EMAIL, PASSWORD));
    }

    private AuthenticationException attemptFailure(Authentication attempt) {
        AuthenticationException thrown = null;
        try {
            provider.authenticate(attempt);
        } catch (AuthenticationException e) {
            thrown = e;
        }
        assertThat(thrown).as("an AuthenticationException was expected").isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(PASSWORD);
        }
        return thrown;
    }
}