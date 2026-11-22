package ma.ensias.ecommerce.user.security.mtls;

import ma.ensias.ecommerce.user.security.TestJwtDecoderConfig;
import ma.ensias.ecommerce.user.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP behaviour of the endpoint reserved to the authorization server, through the full security chain. The
 * certificate is given through the request attribute (no TLS here): this proves the RULES; the handshake itself is
 * proved by ServiceMtlsIntegrationTest.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:users-service-rule-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class ServiceEndpointsAccessTest {

    private static final String BODY = "{\"email\":\"hamza.belhaj@example.com\",\"password\":\"Demo@2026\"}";

    @Autowired
    private MockMvc mvc;

    private static MockHttpServletRequestBuilder verify() {
        return post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static String bearer() {
        return "Bearer " + TestJwtSupport.validToken();
    }

    // ---------------------------------------------------------------- granted

    @Test
    void theAuthorizationServerIsServedWithoutAnyToken() throws Exception {
        mvc.perform(verify().with(ClientCertificate.authorizationServer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("hamza.belhaj@example.com"));
    }

    // ---------------------------------------------------------------- 401: no certificate

    @Test
    void withoutCertificateTheAnswerIs401() throws Exception {
        mvc.perform(verify())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void withoutCertificateTheAnswerIs401EvenWithAValidToken() throws Exception {
        mvc.perform(verify().header("Authorization", bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void aHeaderCannotStandInForTheCertificate() throws Exception {
        mvc.perform(verify()
                        .header("jakarta.servlet.request.X509Certificate", "CN=authorization-server")
                        .header("X-Forwarded-Client-Cert", "CN=authorization-server")
                        .header("X-Client-CN", "authorization-server"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 403: certificate present, service not allowed

    @Test
    void anotherProjectServiceGets403() throws Exception {
        mvc.perform(verify().with(ClientCertificate.of("order-service")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(content().string(not(containsString("order-service"))));
    }

    @Test
    void aCertificateSignedByTheCaButWithAnUnknownCnGets403() throws Exception {
        mvc.perform(verify().with(ClientCertificate.of("rogue-service")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(content().string(not(containsString("rogue-service"))));
    }

    @Test
    void anotherServiceWithAValidTokenStillGets403() throws Exception {
        mvc.perform(verify().with(ClientCertificate.of("order-service")).header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- the certificate opens that endpoint only

    @Test
    void theCertificateOfTheAuthorizationServerGivesAccessToNothingElse() throws Exception {
        mvc.perform(get("/users").with(ClientCertificate.authorizationServer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/auth/verify").with(ClientCertificate.authorizationServer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/other").with(ClientCertificate.authorizationServer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsStillAnswerWithoutCertificateOrWithAnyCertificate() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(ClientCertificate.of("rogue-service"))).andExpect(status().isOk());
    }
}
