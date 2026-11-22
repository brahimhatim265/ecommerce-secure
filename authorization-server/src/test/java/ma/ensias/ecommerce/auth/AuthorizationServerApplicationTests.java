package ma.ensias.ecommerce.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationServerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void discoveryDocumentExposesStandardOidcMetadata() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://localhost:9000"))
                .andExpect(jsonPath("$.authorization_endpoint").isNotEmpty())
                .andExpect(jsonPath("$.token_endpoint").isNotEmpty())
                .andExpect(jsonPath("$.jwks_uri").isNotEmpty());
    }

    @Test
    void jwksExposesPublicKeysOnly() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isNotEmpty())
                .andExpect(jsonPath("$.keys[*].d").doesNotExist());
    }
}