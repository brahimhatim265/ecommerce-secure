package ma.ensias.ecommerce.user.security;

import ma.ensias.ecommerce.user.controller.UserController;
import ma.ensias.ecommerce.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static ma.ensias.ecommerce.user.security.TestJwtSupport.ISSUER;
import static ma.ensias.ecommerce.user.security.TestJwtSupport.TRUSTED_KEY;
import static ma.ensias.ecommerce.user.security.TestJwtSupport.UNTRUSTED_KEY;
import static ma.ensias.ecommerce.user.security.TestJwtSupport.token;
import static ma.ensias.ecommerce.user.security.TestJwtSupport.validToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token validation on a protected endpoint. The decoder trusts a key generated in memory and applies the same
 * validators as production (issuer, timestamps, roles claim), so no authorization server is needed.
 */
@WebMvcTest(UserController.class)
@Import(WebSliceSecurityTestConfig.class)
class JwtValidationTest {

    private static final String PROTECTED_URL = "/users";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @Test
    void requestWithoutTokenGets401WithApiErrorAndBearerChallenge() throws Exception {
        mvc.perform(get(PROTECTED_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.path").value(PROTECTED_URL))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    void rejectedTokenGets401WithTheSameGenericMessage(String description, String token) throws Exception {
        mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired token"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestIdentities.adminBearerToken()))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> rejectedTokens() {
        Instant inFifteenMinutes = Instant.now().plus(Duration.ofMinutes(15));
        return Stream.of(
                Arguments.of("malformed token", "not-a-jwt"),
                Arguments.of("expired token",
                        token(TRUSTED_KEY, ISSUER, Instant.now().minus(Duration.ofHours(1)), true)),
                Arguments.of("token from another issuer",
                        token(TRUSTED_KEY, "http://evil.example.com", inFifteenMinutes, true)),
                Arguments.of("token signed by another key",
                        token(UNTRUSTED_KEY, ISSUER, inFifteenMinutes, true)),
                Arguments.of("token with a modified payload", withModifiedPayload(validToken())),
                Arguments.of("token without roles claim (ID token case)",
                        token(TRUSTED_KEY, ISSUER, inFifteenMinutes, false)));
    }

    private static String withModifiedPayload(String token) {
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        int middle = payload.length / 2;
        payload[middle] = payload[middle] == 'A' ? 'B' : 'A';
        return parts[0] + "." + new String(payload) + "." + parts[2];
    }
}
