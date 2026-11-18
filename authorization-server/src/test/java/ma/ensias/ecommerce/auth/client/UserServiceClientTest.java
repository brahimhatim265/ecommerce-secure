package ma.ensias.ecommerce.auth.client;

import ma.ensias.ecommerce.auth.dto.VerifiedIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserServiceClientTest {

    private static final String BASE_URL = "http://localhost:8081";
    private static final String VERIFY_URL = BASE_URL + "/internal/auth/verify";
    private static final String EMAIL = "hamza.belhaj@example.com";
    private static final String PASSWORD = "Demo@2026";
    private static final UUID HAMZA_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final String IDENTITY_JSON = """
            {"id":"00000000-0000-4000-8000-000000000001","email":"hamza.belhaj@example.com",
             "firstName":"Hamza","lastName":"Belhaj","roles":["CUSTOMER"],"unknownField":"ignored"}""";

    private MockRestServiceServer server;
    private UserServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UserServiceClient(builder.build());
    }

    @Test
    void returnsIdentityOn200AndIgnoresUnknownFields() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withSuccess(IDENTITY_JSON, MediaType.APPLICATION_JSON));

        VerifiedIdentity identity = client.verify(EMAIL, PASSWORD);

        assertThat(identity.id()).isEqualTo(HAMZA_ID);
        assertThat(identity.email()).isEqualTo(EMAIL);
        assertThat(identity.firstName()).isEqualTo("Hamza");
        assertThat(identity.lastName()).isEqualTo("Belhaj");
        assertThat(identity.roles()).containsExactly("CUSTOMER");
        server.verify();
    }

    @Test
    void sendsAJsonPostWithEmailAndPassword() {
        server.expect(requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").value(PASSWORD))
                .andRespond(withSuccess(IDENTITY_JSON, MediaType.APPLICATION_JSON));

        client.verify(EMAIL, PASSWORD);

        server.verify();
    }

    @Test
    void maps401ToBadCredentials() {
        assertThat(failureFor(withStatus(HttpStatus.UNAUTHORIZED))).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void maps403ToDisabled() {
        assertThat(failureFor(withStatus(HttpStatus.FORBIDDEN))).isInstanceOf(DisabledException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 503})
    void mapsOtherErrorStatusesToServiceFailure(int status) {
        assertThat(failureFor(withStatus(HttpStatus.valueOf(status))))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void mapsTimeoutToServiceFailure() {
        assertThat(failureFor(request -> {
            throw new SocketTimeoutException("Read timed out");
        })).isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void mapsUnreachableServiceToServiceFailure() {
        assertThat(failureFor(request -> {
            throw new ConnectException("Connection refused");
        })).isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void mapsInvalidJsonToServiceFailure() {
        assertThat(failureFor(withSuccess("this is not json", MediaType.APPLICATION_JSON)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"hamza.belhaj@example.com\"}"})
    void mapsIncompleteIdentityToServiceFailure(String body) {
        assertThat(failureFor(withSuccess(body, MediaType.APPLICATION_JSON)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    /** Runs a failing call, checks that nothing in the exception chain leaks the password, returns the exception. */
    private AuthenticationException failureFor(ResponseCreator response) {
        server.expect(requestTo(VERIFY_URL)).andRespond(response);

        AuthenticationException thrown = null;
        try {
            client.verify(EMAIL, PASSWORD);
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