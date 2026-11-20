package ma.ensias.ecommerce.order.security;

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

/**
 * The catalog (read-only) and the health probe are public; everything next to them requires a token
 * (full application context, seeded database).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:orders-security-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class PublicUrlsSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthProbeIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void productListIsPublic() throws Exception {
        mvc.perform(get("/products")).andExpect(status().isOk());
    }

    @Test
    void productByIdIsPublic() throws Exception {
        mvc.perform(get("/products/1")).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} {1} requires a token")
    @MethodSource("protectedRequests")
    void everythingElseRequiresAToken(HttpMethod method, String path) throws Exception {
        mvc.perform(request(method, path)).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> protectedRequests() {
        return Stream.of(
                // same path as a public URL, other method: catalog management
                Arguments.of(HttpMethod.POST, "/products"),
                Arguments.of(HttpMethod.PUT, "/products/1"),
                Arguments.of(HttpMethod.DELETE, "/products/1"),
                Arguments.of(HttpMethod.POST, "/actuator/health"),
                // same prefix as a public URL, other path
                Arguments.of(HttpMethod.GET, "/products/1/anything"),
                Arguments.of(HttpMethod.GET, "/actuator/info"),
                // orders
                Arguments.of(HttpMethod.GET, "/orders"),
                Arguments.of(HttpMethod.GET, "/orders/1"),
                Arguments.of(HttpMethod.POST, "/orders"),
                // unknown path: denied by default
                Arguments.of(HttpMethod.GET, "/does-not-exist"));
    }
}
