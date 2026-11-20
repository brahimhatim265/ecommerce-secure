package ma.ensias.ecommerce.user.security;

import ma.ensias.ecommerce.user.controller.UserController;
import ma.ensias.ecommerce.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** When the authorization server cannot be reached, a request with a token gets a clean 503, not a fake 401. */
@WebMvcTest(UserController.class)
@Import(WebSliceSecurityTestConfig.class)
class AuthorizationServerUnavailableTest {

    private static final String PROTECTED_URL = "/users";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void decoderThatCannotBeInitialisedGives503() throws Exception {
        when(jwtDecoder.decode(anyString()))
                .thenThrow(new JwtDecoderInitializationException("not reachable", new IOException("refused")));

        mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, "Bearer some.token.value"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Authentication service unavailable"))
                .andExpect(jsonPath("$.path").value(PROTECTED_URL))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void failureToFetchTheSigningKeysGives503() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("Couldn't retrieve remote JWK set"));

        mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, "Bearer some.token.value"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Authentication service unavailable"))
                .andExpect(jsonPath("$.path").value(PROTECTED_URL));
    }

    @Test
    void aTokenRefusedByValidationStillGets401() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("expired"));

        mvc.perform(get(PROTECTED_URL).header(HttpHeaders.AUTHORIZATION, "Bearer some.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }
}
