package ma.ensias.ecommerce.payment.controller;

import ma.ensias.ecommerce.payment.exception.NotOwnerException;
import ma.ensias.ecommerce.payment.security.mtls.ClientCertificate;
import ma.ensias.ecommerce.payment.service.PaymentService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ma.ensias.ecommerce.payment.security.TestIdentities;
import ma.ensias.ecommerce.payment.security.WebSliceSecurityTestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static ma.ensias.ecommerce.payment.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.payment.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.payment.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail of real HTTP requests: exactly ONE line per request, with the reason and the identity that the
 * demonstration (scenario S48) shows.
 */
@WebMvcTest(PaymentController.class)
@Import(WebSliceSecurityTestConfig.class)
class AuditTrailHttpTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger audit = (Logger) LoggerFactory.getLogger("AUDIT");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;


    @BeforeEach
    void capture() {
        audit.setLevel(Level.INFO);
        logs.start();
        audit.addAppender(logs);
    }

    @AfterEach
    void release() {
        audit.detachAppender(logs);
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String line(String decision, String reason, String method, String path, UUID user, String caller) {
        return "AUDIT decision=" + decision + " reason=" + reason + " method=" + method + " path=" + path
                + " user=" + (user == null ? "-" : user) + " caller=" + caller;
    }

    private static String body(UUID owner) {
        return "{\"orderId\":42,\"ownerId\":\"" + owner + "\",\"amount\":6780.00,"
                + "\"currency\":\"MAD\",\"method\":\"CASH_ON_DELIVERY\"}";
    }

    @Test
    void theOrderServicePayingForSomeoneElseIsRefusedAsNotTheOwnerWithItsName() throws Exception {
        mvc.perform(post("/payments").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body(OTHER_CUSTOMER_ID))
                        .with(ClientCertificate.orderService()).with(TestIdentities.customer()))
                .andExpect(status().isForbidden());

        assertThat(lines()).containsExactly(
                line("DENY", "not_owner", "POST", "/payments", CUSTOMER_ID, "order-service"));
    }

    @Test
    void aUserWithoutTheCertificateOfTheOrderServiceIsRefusedForMissingCertificate() throws Exception {
        mvc.perform(post("/payments").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body(CUSTOMER_ID))
                        .with(TestIdentities.customer()))
                .andExpect(status().isUnauthorized());

        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).startsWith("AUDIT decision=DENY reason=missing_certificate method=POST path=/payments");
    }

    @Test
    void theOrderServiceWithoutAUserIsRefusedForMissingToken() throws Exception {
        mvc.perform(post("/payments").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body(CUSTOMER_ID))
                        .with(ClientCertificate.orderService()))
                .andExpect(status().isUnauthorized());

        assertThat(lines()).containsExactly(
                line("DENY", "missing_token", "POST", "/payments", null, "order-service"));
    }

    @Test
    void anotherCustomerOnAPaymentIsWrittenAsNotTheOwnerEvenThoughTheAnswerIsA404() throws Exception {
        when(paymentService.getPayment(eq(7L), any())).thenThrow(new NotOwnerException("Payment not found: 7"));

        mvc.perform(get("/payments/7").with(TestIdentities.otherCustomer())).andExpect(status().isNotFound());

        assertThat(lines()).containsExactly(line("DENY", "not_owner", "GET", "/payments/7", OTHER_CUSTOMER_ID, "-"));
    }

    @Test
    void anAdministratorListingThePaymentsIsAllowed() throws Exception {
        mvc.perform(get("/payments").with(TestIdentities.admin())).andExpect(status().isOk());

        assertThat(lines()).containsExactly(line("ALLOW", "-", "GET", "/payments", ADMIN_ID, "-"));
    }
}
