package ma.ensias.ecommerce.payment.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Profile "demo": Swagger UI and the OpenAPI definition are public and declare the Bearer scheme. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-swagger-on-${random.uuid};DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class SwaggerDemoProfileTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void openApiDefinitionIsPublicAndDeclaresTheBearerScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").exists());
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void demoProfileOpensNothingElse() throws Exception {
        mvc.perform(get("/payments")).andExpect(status().isUnauthorized());
    }
}
