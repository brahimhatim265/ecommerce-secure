package ma.ensias.ecommerce.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ma.ensias.ecommerce.auth.security.AuthenticatedUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import java.util.Set;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;


@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationCodePkceFlowTests {

    private static final String CLIENT_ID = "ecommerce-web";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/authorized";
    private static final String SCOPE = "openid profile email";
    private static final String STATE = "state-123";
    private static final String ISSUER = "http://localhost:9000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- Nominal case ----------

    @Test
    void authorizationRequestRedirectsWithCodeAndSameState() throws Exception {
        PkcePair pkce = newPkcePair();

        MvcResult result = authorize(pkce.challenge());

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT_URI);
        assertThat(queryParam(location, "code")).isNotBlank();
        assertThat(queryParam(location, "state")).isEqualTo(STATE);
    }

    @Test
    void tokenResponseHasAccessAndIdTokenButNoRefreshToken() throws Exception {
        JsonNode tokens = obtainTokens();

        assertThat(tokens.get("access_token").asText()).isNotBlank();
        assertThat(tokens.get("id_token").asText()).isNotBlank();
        assertThat(tokens.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expires_in").asLong()).isBetween(895L, 900L);
        assertThat(tokens.has("refresh_token")).isFalse();
    }

    @Test
    void accessTokenIsAJwtSignedWithAPublishedKey() throws Exception {
        JsonNode tokens = obtainTokens();
        SignedJWT jwt = SignedJWT.parse(tokens.get("access_token").asText());

        String jwks = mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JWK key = JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID());

        assertThat(key).isNotNull();
        assertThat(jwt.verify(new RSASSAVerifier(key.toRSAKey().toRSAPublicKey()))).isTrue();

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).contains(CLIENT_ID);
        long lifetimeSeconds =
                (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
        assertThat(lifetimeSeconds).isEqualTo(900L);
        assertThat(claims.getStringListClaim("scope"))
                .containsExactlyInAnyOrder("openid", "profile", "email");
    }

    // ---------- Failure cases ----------

    @Test
    void wrongCodeVerifierIsRejected() throws Exception {
        PkcePair pkce = newPkcePair();
        String code = codeFrom(authorize(pkce.challenge()));

        exchange(code, newPkcePair().verifier())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void authorizationCodeCannotBeUsedTwice() throws Exception {
        PkcePair pkce = newPkcePair();
        String code = codeFrom(authorize(pkce.challenge()));

        exchange(code, pkce.verifier()).andExpect(status().isOk());

        exchange(code, pkce.verifier())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void authorizationRequestWithoutCodeChallengeIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(
                        get(authorizationUri(CLIENT_ID, REDIRECT_URI, SCOPE, null))
                                .with(authentication(authenticatedUser())))
                .andReturn();

        assertRejectedWithoutCode(result);
    }

    @Test
    void unregisteredRedirectUriIsRejectedWithoutRedirect() throws Exception {
        PkcePair pkce = newPkcePair();

        MvcResult result = mockMvc.perform(
                        get(authorizationUri(CLIENT_ID, "http://127.0.0.1:8080/not-registered",
                                SCOPE, pkce.challenge()))
                                .with(authentication(authenticatedUser())))
                .andExpect(status().is4xxClientError())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void unknownClientIsRejectedWithoutRedirect() throws Exception {
        PkcePair pkce = newPkcePair();

        MvcResult result = mockMvc.perform(
                        get(authorizationUri("unknown-client", REDIRECT_URI, SCOPE, pkce.challenge()))
                                .with(authentication(authenticatedUser())))
                .andExpect(status().is4xxClientError())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void scopeNotAllowedForTheClientIsRejected() throws Exception {
        PkcePair pkce = newPkcePair();

        MvcResult result = mockMvc.perform(
                        get(authorizationUri(CLIENT_ID, REDIRECT_URI, "admin", pkce.challenge()))
                                .with(authentication(authenticatedUser())))
                .andReturn();

        assertRejectedWithoutCode(result);
    }

    @Test
    void unauthenticatedUserIsRedirectedToLoginPage() throws Exception {
        PkcePair pkce = newPkcePair();

        // A browser sends "Accept: text/html"; without it the server answers 401 like for an API call.
        MvcResult result = mockMvc.perform(
                        get(authorizationUri(CLIENT_ID, REDIRECT_URI, SCOPE, pkce.challenge()))
                                .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "client_credentials"})
    void grantTypesOtherThanAuthorizationCodeAreRejected(String grantType) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", grantType)
                        .param("client_id", CLIENT_ID)
                        .param("username", "hamza.belhaj@example.com")
                        .param("password", "irrelevant"))
                .andExpect(status().is4xxClientError())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("access_token");
    }

    // ---------- Helpers ----------

    private JsonNode obtainTokens() throws Exception {
        PkcePair pkce = newPkcePair();
        String code = codeFrom(authorize(pkce.challenge()));

        String body = exchange(code, pkce.verifier())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private MvcResult authorize(String codeChallenge) throws Exception {
        return mockMvc.perform(get(authorizationUri(CLIENT_ID, REDIRECT_URI, SCOPE, codeChallenge))
                        .with(authentication(authenticatedUser())))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private ResultActions exchange(String code, String codeVerifier) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("client_id", CLIENT_ID)
                .param("code_verifier", codeVerifier));
    }

    // The authorization endpoint reads its parameters from the URL query string,
    // so they must be part of the URI (MockMvc .param() would not populate it).
    private static URI authorizationUri(String clientId, String redirectUri, String scope,
                                        String codeChallenge) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scope)
                .queryParam("state", STATE);
        if (codeChallenge != null) {
            builder.queryParam("code_challenge", codeChallenge)
                    .queryParam("code_challenge_method", "S256");
        }
        return builder.build().encode().toUri();
    }

    private static String codeFrom(MvcResult authorization) {
        return queryParam(authorization.getResponse().getRedirectedUrl(), "code");
    }

    // The server may answer with an error page (4xx) or redirect to the registered
    // redirect URI with an "error" parameter. In both cases no code must be issued.
    private static void assertRejectedWithoutCode(MvcResult result) {
        int status = result.getResponse().getStatus();
        if (status >= 300 && status < 400) {
            String location = result.getResponse().getRedirectedUrl();
            assertThat(queryParam(location, "error")).isNotBlank();
            assertThat(queryParam(location, "code")).isNull();
        } else {
            assertThat(status).isBetween(400, 499);
        }
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

    private static Authentication authenticatedUser() {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "hamza.belhaj@example.com", "Hamza", "Belhaj", Set.of("CUSTOMER"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }
}