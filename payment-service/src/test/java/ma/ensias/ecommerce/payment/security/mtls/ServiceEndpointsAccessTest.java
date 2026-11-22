package ma.ensias.ecommerce.payment.security.mtls;

import ma.ensias.ecommerce.payment.security.TestJwtDecoderConfig;
import ma.ensias.ecommerce.payment.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP behaviour of POST /payments, reserved to order-service acting on behalf of a user, through the full security
 * chain. The certificate is given through the request attribute (no TLS here): this proves the RULES; the handshake
 * itself is proved by ServiceMtlsIntegrationTest.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payments-service-rule-${random.uuid};DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class ServiceEndpointsAccessTest {

    private static final String OWNER = TestJwtSupport.SUBJECT;
    private static final String BODY = "{\"orderId\":7001,\"ownerId\":\"" + OWNER + "\",\"amount\":6780.00,"
            + "\"currency\":\"MAD\",\"method\":\"CARD\",\"cardToken\":\"tok_visa_approved\"}";

    @Autowired
    private MockMvc mvc;

    private static MockHttpServletRequestBuilder payment(String idempotencyKey) {
        return post("/payments").header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static String bearer() {
        return "Bearer " + TestJwtSupport.validToken();
    }

    // ---------------------------------------------------------------- granted

    @Test
    void orderServiceWithAValidTokenIsServed() throws Exception {
        mvc.perform(payment("rule-it-1").with(ClientCertificate.orderService()).header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    // ---------------------------------------------------------------- 401

    @Test
    void orderServiceWithoutTokenGets401() throws Exception {
        mvc.perform(payment("rule-it-2").with(ClientCertificate.orderService()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void orderServiceWithAnExpiredTokenGets401() throws Exception {
        String expired = TestJwtSupport.token(TestJwtSupport.TRUSTED_KEY, TestJwtSupport.ISSUER,
                Instant.now().minus(Duration.ofHours(1)), true);

        mvc.perform(payment("rule-it-3").with(ClientCertificate.orderService())
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void orderServiceWithATokenSignedByAnotherKeyGets401() throws Exception {
        String forged = TestJwtSupport.token(TestJwtSupport.UNTRUSTED_KEY, TestJwtSupport.ISSUER,
                Instant.now().plus(Duration.ofMinutes(15)), true);

        mvc.perform(payment("rule-it-4").with(ClientCertificate.orderService())
                        .header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutCertificateTheAnswerIs401() throws Exception {
        mvc.perform(payment("rule-it-5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void withoutCertificateTheAnswerIs401EvenWithAValidToken() throws Exception {
        mvc.perform(payment("rule-it-6").header("Authorization", bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aHeaderCannotStandInForTheCertificate() throws Exception {
        mvc.perform(payment("rule-it-7").header("Authorization", bearer())
                        .header("jakarta.servlet.request.X509Certificate", "CN=order-service")
                        .header("X-Forwarded-Client-Cert", "CN=order-service")
                        .header("X-Client-CN", "order-service"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 403

    @ParameterizedTest(name = "the certificate {0} with a valid token gets 403")
    @ValueSource(strings = {"authorization-server", "rogue-service"})
    void anotherServiceWithAValidTokenGets403(String keystore) throws Exception {
        mvc.perform(payment("rule-it-8").with(ClientCertificate.of(keystore)).header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(content().string(not(containsString(keystore))));
    }

    @Test
    void anotherServiceWithoutTokenGets403BecauseTheCertificateIsJudgedFirst() throws Exception {
        mvc.perform(payment("rule-it-9").with(ClientCertificate.of("rogue-service")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    // ---------------------------------------------------------------- only POST /payments is reserved

    @Test
    void theCertificateOfOrderServiceGivesAccessToNothingWithoutAToken() throws Exception {
        mvc.perform(get("/payments").param("ownerId", OWNER).with(ClientCertificate.orderService()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherEndpointsStayReachableWithATokenAloneAsBefore() throws Exception {
        mvc.perform(get("/payments").param("ownerId", OWNER).header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpointsStillAnswerWithoutCertificateOrWithAnyCertificate() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(ClientCertificate.of("rogue-service"))).andExpect(status().isOk());
    }
}
