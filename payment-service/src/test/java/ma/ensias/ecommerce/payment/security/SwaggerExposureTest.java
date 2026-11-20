package ma.ensias.ecommerce.payment.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static ma.ensias.ecommerce.payment.security.TestJwtSupport.customer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default profile: Swagger UI and the OpenAPI definition are NOT exposed. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-swagger-off-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class SwaggerExposureTest {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "{0} is denied without a token")
    @ValueSource(strings = {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"})
    void documentationIsNotPublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} does not exist even with a token")
    @ValueSource(strings = {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"})
    void documentationIsNotServedEvenToAnAuthenticatedUser(String path) throws Exception {
        mvc.perform(get(path).with(customer())).andExpect(status().isNotFound());
    }
}
