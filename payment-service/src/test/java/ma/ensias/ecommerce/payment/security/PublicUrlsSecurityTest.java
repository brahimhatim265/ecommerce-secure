package ma.ensias.ecommerce.payment.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The health probe is the only public URL; everything else requires a token (full application context). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payments-security-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class PublicUrlsSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthProbeIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} {1} requires a token")
    @MethodSource("protectedRequests")
    void everythingElseRequiresAToken(HttpMethod method, String path) throws Exception {
        mvc.perform(request(method, path)).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> protectedRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/payments"),
                Arguments.of(HttpMethod.GET, "/payments"),
                Arguments.of(HttpMethod.GET, "/payments/1"),
                Arguments.of(HttpMethod.GET, "/payments/order/1"),
                Arguments.of(HttpMethod.POST, "/payments/1/collect"),
                Arguments.of(HttpMethod.DELETE, "/payments/1"),
                // same path as the public URL, other method
                Arguments.of(HttpMethod.POST, "/actuator/health"),
                Arguments.of(HttpMethod.GET, "/actuator/info"),
                // unknown path: denied by default
                Arguments.of(HttpMethod.GET, "/does-not-exist"));
    }
}
