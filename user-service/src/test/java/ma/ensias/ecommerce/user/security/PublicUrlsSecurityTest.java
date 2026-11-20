package ma.ensias.ecommerce.user.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public URLs answer without a token; everything next to them stays protected (full application context). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:users-security-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class PublicUrlsSecurityTest {

    private static final String HAMZA_ID = "00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Test
    void healthProbeIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void registrationIsPublic() throws Exception {
        String body = "{\"email\":\"security.test@example.com\",\"password\":\"Secret123\","
                + "\"firstName\":\"Test\",\"lastName\":\"Security\"}";
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Provisional exception: the authorization server has no JWT when it verifies a login. */
    @Test
    void credentialVerificationIsPublicForThisMethodAndPathOnly() throws Exception {
        String body = "{\"email\":\"hamza.belhaj@example.com\",\"password\":\"Demo@2026\"}";
        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} {1} requires a token")
    @MethodSource("protectedRequests")
    void everythingElseRequiresAToken(HttpMethod method, String path) throws Exception {
        mvc.perform(request(method, path)).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> protectedRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/users"),
                Arguments.of(HttpMethod.GET, "/users/" + HAMZA_ID),
                Arguments.of(HttpMethod.PUT, "/users/" + HAMZA_ID),
                Arguments.of(HttpMethod.PATCH, "/users/" + HAMZA_ID + "/status"),
                Arguments.of(HttpMethod.DELETE, "/users/" + HAMZA_ID),
                // same path as a public URL, other method
                Arguments.of(HttpMethod.GET, "/internal/auth/verify"),
                Arguments.of(HttpMethod.POST, "/actuator/health"),
                // same prefix as a public URL, other path
                Arguments.of(HttpMethod.POST, "/internal/auth/other"),
                Arguments.of(HttpMethod.GET, "/actuator/info"),
                // unknown path: denied by default
                Arguments.of(HttpMethod.GET, "/does-not-exist"));
    }
}
