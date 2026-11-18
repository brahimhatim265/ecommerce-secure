package ma.ensias.ecommerce.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full flow: login form -> user-service (simulated) -> Authorization Code + PKCE -> token. */
@SpringBootTest
@AutoConfigureMockMvc
class LoginViaUserServiceFlowTests {

    private static final String CLIENT_ID = "ecommerce-web";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/authorized";
    private static final String SCOPE = "openid profile email";
    private static final String STATE = "state-123";

    private static final String VERIFY_URL = "http://localhost:8081/internal/auth/verify";
    private static final String HAMZA_EMAIL = "hamza.belhaj@example.com";
    private static final String HAMZA_ID = "00000000-0000-4000-8000-000000000001";
    private static final String PASSWORD = "Demo@2026";
    private static final String HAMZA_JSON = """
            {"id":"00000000-0000-4000-8000-000000000001","email":"hamza.belhaj@example.com",
             "firstName":"Hamza","lastName":"Belhaj","roles":["CUSTOMER"]}""";

    /** Replaces the real user-service client by one bound to a mock server (no network). */
    @TestConfiguration
    static class MockUserServiceConfig {

        private final RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        @Bean
        MockRestServiceServer userServiceServer() {
            return server;
        }

        @Bean
        @Primary
        RestClient mockedUserServiceRestClient() {
            return builder.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockRestServiceServer userService;

    @BeforeEach
    void resetUserServiceMock() {
        userService.reset();
    }

    // ---------- Nominal case ----------

    @Test
    void formLoginThenPkceGivesATokenWhoseSubIsTheUserUuid() throws Exception {
        userService.expect(requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value(HAMZA_EMAIL))
                .andExpect(jsonPath("$.password").value(PASSWORD))
                .andRespond(withSuccess(HAMZA_JSON, MediaType.APPLICATION_JSON));
        PkcePair pkce = newPkcePair();

        MockHttpSession session = startAuthorization(pkce);
        MvcResult login = login(session, HAMZA_EMAIL, PASSWORD);
        String code = completeAuthorization(session, login);

        JsonNode tokens = exchange(code, pkce.verifier());
        assertThat(subjectOf(tokens.get("access_token").asText())).isEqualTo(HAMZA_ID);
        assertThat(subjectOf(tokens.get("id_token").asText())).isEqualTo(HAMZA_ID);
        assertThat(tokens.has("refresh_token")).isFalse();
        userService.verify();
    }

    // ---------- Failure cases ----------

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameFailure() throws Exception {
        userService.expect(requestTo(VERIFY_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        userService.expect(requestTo(VERIFY_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        PkcePair pkce = newPkcePair();

        MockHttpSession wrongPassword = startAuthorization(pkce);
        MvcResult first = login(wrongPassword, HAMZA_EMAIL, "wrong-password");
        MockHttpSession unknownEmail = startAuthorization(pkce);
        MvcResult second = login(unknownEmail, "nobody@example.com", "wrong-password");

        assertThat(first.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(second.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(errorPage(wrongPassword)).contains("Invalid credentials");
        assertThat(errorPage(unknownEmail)).contains("Invalid credentials");
        assertNoCodeIssued(wrongPassword, pkce);
        assertNoCodeIssued(unknownEmail, pkce);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500})
    void failedLoginIssuesNoCodeAndRevealsNoInternalDetail(int userServiceStatus) throws Exception {
        userService.expect(requestTo(VERIFY_URL))
                .andRespond(withStatus(HttpStatus.valueOf(userServiceStatus)));
        PkcePair pkce = newPkcePair();

        MockHttpSession session = startAuthorization(pkce);
        MvcResult login = login(session, HAMZA_EMAIL, PASSWORD);

        assertThat(login.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        String page = errorPage(session);
        assertThat(page).doesNotContain("user-service").doesNotContain("Account disabled")
                .doesNotContain("HTTP 500").doesNotContain(PASSWORD);
        assertNoCodeIssued(session, pkce);
    }

    @Test
    void serverStaysUpWhenUserServiceIsDownAndLoginWorksAgainAfterwards() throws Exception {
        // MockRestServiceServer forbids adding expectations once a request was made: declare both up front.
        // They are consumed in order: first the outage, then the recovery.
        userService.expect(requestTo(VERIFY_URL)).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });
        userService.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess(HAMZA_JSON, MediaType.APPLICATION_JSON));
        PkcePair pkce = newPkcePair();

        MockHttpSession failedSession = startAuthorization(pkce);
        MvcResult failed = login(failedSession, HAMZA_EMAIL, PASSWORD);
        assertThat(failed.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertNoCodeIssued(failedSession, pkce);

        // user-service is back: a new attempt succeeds
        MockHttpSession retrySession = startAuthorization(pkce);
        MvcResult retry = login(retrySession, HAMZA_EMAIL, PASSWORD);
        String code = completeAuthorization(retrySession, retry);

        assertThat(subjectOf(exchange(code, pkce.verifier()).get("access_token").asText())).isEqualTo(HAMZA_ID);
        userService.verify();
    }

    @Test
    void loginWithoutCsrfTokenIsRejectedAndUserServiceIsNotCalled() throws Exception {
        // no expectation registered: any call to user-service would fail the test
        mockMvc.perform(post("/login")
                        .param("username", HAMZA_EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());

        userService.verify();
    }

    // ---------- Helpers ----------

    /** Starts the flow as a browser would: not authenticated, so the server redirects to the login page. */
    private MockHttpSession startAuthorization(PkcePair pkce) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(get(authorizationUri(pkce.challenge()))
                        .accept(MediaType.TEXT_HTML)
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
        return session;
    }

    private MvcResult login(MockHttpSession session, String email, String password) throws Exception {
        // "username" is the field name imposed by Spring Security: the user types their email in it
        return mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password)
                        .with(csrf())
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    /** After a successful login the browser is sent back to the saved authorization request. */
    private String completeAuthorization(MockHttpSession session, MvcResult login) throws Exception {
        String savedRequest = login.getResponse().getRedirectedUrl();
        assertThat(savedRequest).contains("/oauth2/authorize");

        MvcResult result = mockMvc.perform(get(URI.create(savedRequest)).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT_URI);
        assertThat(queryParam(location, "state")).isEqualTo(STATE);
        String code = queryParam(location, "code");
        assertThat(code).isNotBlank();
        return code;
    }

    /** After a failed login the user is still anonymous: asking for a code again leads to the login page. */
    private void assertNoCodeIssued(MockHttpSession session, PkcePair pkce) throws Exception {
        MvcResult result = mockMvc.perform(get(authorizationUri(pkce.challenge()))
                        .accept(MediaType.TEXT_HTML)
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).endsWith("/login");
        assertThat(location).doesNotContain("code=");
    }

    private String errorPage(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/login?error").accept(MediaType.TEXT_HTML).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode exchange(String code, String codeVerifier) throws Exception {
        ResultActions result = mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("client_id", CLIENT_ID)
                .param("code_verifier", codeVerifier));
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static String subjectOf(String jwt) throws Exception {
        return SignedJWT.parse(jwt).getJWTClaimsSet().getSubject();
    }

    private static URI authorizationUri(String codeChallenge) {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", SCOPE)
                .queryParam("state", STATE)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUri();
    }

    private static String queryParam(String url, String name) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
    }

    private static PkcePair newPkcePair() throws NoSuchAlgorithmException {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String verifier = encoder.encodeToString(random);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new PkcePair(verifier, encoder.encodeToString(digest));
    }

    private record PkcePair(String verifier, String challenge) {
    }
}