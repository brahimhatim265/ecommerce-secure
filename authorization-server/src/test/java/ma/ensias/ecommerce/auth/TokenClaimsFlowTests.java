package ma.ensias.ecommerce.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Claims of the issued tokens, checked through the full flow: login form -> user-service (simulated) -> PKCE. */
@SpringBootTest
@AutoConfigureMockMvc
class TokenClaimsFlowTests {

    private static final String CLIENT_ID = "ecommerce-web";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/authorized";
    private static final String STATE = "state-123";
    private static final String ALL_SCOPES = "openid profile email";
    private static final String VERIFY_URL = "http://localhost:8081/internal/auth/verify";
    private static final String PASSWORD = "Demo@2026";

    private static final String HAMZA_ID = "00000000-0000-4000-8000-000000000001";
    private static final String HAMZA_EMAIL = "hamza.belhaj@example.com";
    private static final String HAMZA_JSON = """
            {"id":"00000000-0000-4000-8000-000000000001","email":"hamza.belhaj@example.com",
             "firstName":"Hamza","lastName":"Belhaj","roles":["CUSTOMER"]}""";

    private static final String ADMIN_ID = "00000000-0000-4000-8000-000000000008";
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_JSON = """
            {"id":"00000000-0000-4000-8000-000000000008","email":"admin@example.com",
             "firstName":"Administrateur","lastName":"Boutique","roles":["ADMIN"]}""";

    private static final List<String> FORBIDDEN_CLAIMS = List.of(
            "password", "passwordHash", "phone", "address", "city", "active", "email_verified");

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

    @Test
    void customerAccessTokenCarriesSubEmailNameAndRoles() throws Exception {
        JsonNode tokens = loginAndGetTokens(HAMZA_EMAIL, HAMZA_JSON, ALL_SCOPES);

        JWTClaimsSet claims = claimsOf(tokens.get("access_token").asText());

        assertThat(claims.getSubject()).isEqualTo(HAMZA_ID);
        assertThat(claims.getStringClaim("email")).isEqualTo("hamza.belhaj@example.com");
        assertThat(claims.getStringClaim("name")).isEqualTo("Hamza Belhaj");
        assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER");
    }

    @Test
    void administratorAccessTokenCarriesTheAdminRole() throws Exception {
        JsonNode tokens = loginAndGetTokens(ADMIN_EMAIL, ADMIN_JSON, ALL_SCOPES);

        JWTClaimsSet claims = claimsOf(tokens.get("access_token").asText());

        assertThat(claims.getSubject()).isEqualTo(ADMIN_ID);
        assertThat(claims.getStringClaim("email")).isEqualTo("admin@example.com");
        assertThat(claims.getStringClaim("name")).isEqualTo("Administrateur Boutique");
        assertThat(claims.getStringListClaim("roles")).containsExactly("ADMIN");
    }

    @Test
    void idTokenCarriesEmailAndNameButNoRoles() throws Exception {
        JsonNode tokens = loginAndGetTokens(HAMZA_EMAIL, HAMZA_JSON, ALL_SCOPES);

        JWTClaimsSet claims = claimsOf(tokens.get("id_token").asText());

        assertThat(claims.getSubject()).isEqualTo(HAMZA_ID);
        assertThat(claims.getStringClaim("email")).isEqualTo("hamza.belhaj@example.com");
        assertThat(claims.getStringClaim("name")).isEqualTo("Hamza Belhaj");
        assertThat(claims.getClaims()).doesNotContainKey("roles");
    }

    @Test
    void noForbiddenClaimIsPresentInAnyToken() throws Exception {
        JsonNode tokens = loginAndGetTokens(HAMZA_EMAIL, HAMZA_JSON, ALL_SCOPES);

        Map<String, Object> accessClaims = claimsOf(tokens.get("access_token").asText()).getClaims();
        Map<String, Object> idClaims = claimsOf(tokens.get("id_token").asText()).getClaims();

        assertThat(accessClaims).doesNotContainKeys(FORBIDDEN_CLAIMS.toArray(String[]::new));
        assertThat(idClaims).doesNotContainKeys(FORBIDDEN_CLAIMS.toArray(String[]::new));
    }

    @Test
    void openidScopeAloneGivesRolesButNoEmailNorName() throws Exception {
        JsonNode tokens = loginAndGetTokens(HAMZA_EMAIL, HAMZA_JSON, "openid");

        Map<String, Object> accessClaims = claimsOf(tokens.get("access_token").asText()).getClaims();
        Map<String, Object> idClaims = claimsOf(tokens.get("id_token").asText()).getClaims();

        assertThat(accessClaims).doesNotContainKeys("email", "name").containsEntry("roles", List.of("CUSTOMER"));
        assertThat(idClaims).doesNotContainKeys("email", "name", "roles");
    }

    @Test
    void userInfoEndpointReturnsSubEmailAndName() throws Exception {
        JsonNode tokens = loginAndGetTokens(HAMZA_EMAIL, HAMZA_JSON, ALL_SCOPES);

        String body = mockMvc.perform(get("/userinfo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("access_token").asText()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode userInfo = objectMapper.readTree(body);
        assertThat(userInfo.get("sub").asText()).isEqualTo(HAMZA_ID);
        assertThat(userInfo.get("email").asText()).isEqualTo("hamza.belhaj@example.com");
        assertThat(userInfo.get("name").asText()).isEqualTo("Hamza Belhaj");
    }

    // ---------- Helpers ----------

    private JsonNode loginAndGetTokens(String email, String userServiceJson, String scope) throws Exception {
        userService.expect(requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(userServiceJson, MediaType.APPLICATION_JSON));
        PkcePair pkce = newPkcePair();

        MockHttpSession session = startAuthorization(pkce, scope);
        MvcResult login = login(session, email);
        String code = completeAuthorization(session, login);

        JsonNode tokens = exchange(code, pkce.verifier());
        userService.verify();
        return tokens;
    }

    /** Starts the flow as a browser would: not authenticated, so the server redirects to the login page. */
    private MockHttpSession startAuthorization(PkcePair pkce, String scope) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(get(authorizationUri(pkce.challenge(), scope))
                        .accept(MediaType.TEXT_HTML)
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
        return session;
    }

    private MvcResult login(MockHttpSession session, String email) throws Exception {
        // "username" is the field name imposed by Spring Security: the user types their email in it
        return mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", PASSWORD)
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
        String code = queryParam(location, "code");
        assertThat(code).isNotBlank();
        return code;
    }

    private JsonNode exchange(String code, String codeVerifier) throws Exception {
        String body = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", CLIENT_ID)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static JWTClaimsSet claimsOf(String jwt) throws Exception {
        return SignedJWT.parse(jwt).getJWTClaimsSet();
    }

    private static URI authorizationUri(String codeChallenge, String scope) {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", scope)
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