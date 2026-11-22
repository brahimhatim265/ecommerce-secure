package ma.ensias.ecommerce.payment.integration;

import ma.ensias.ecommerce.payment.security.mtls.ClientCertificate;
import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.payment.repository.PaymentRepository;
import ma.ensias.ecommerce.payment.security.TestJwtDecoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static ma.ensias.ecommerce.payment.security.TestJwtSupport.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full-stack tests of payment-service. A fixed seed makes tok_random reproducible. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payments-it-${random.uuid};DB_CLOSE_DELAY=-1",
        "payment.simulation.random.seed=2026"})
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
class PaymentApiIntegrationTest {

    private static final String OWNER = "00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    private static String card(long orderId, String amount, String token) {
        return "{\"orderId\":" + orderId + ",\"ownerId\":\"" + OWNER + "\",\"amount\":" + amount
                + ",\"currency\":\"MAD\",\"method\":\"CARD\",\"cardToken\":\"" + token + "\"}";
    }

    private static String cash(long orderId, String amount) {
        return "{\"orderId\":" + orderId + ",\"ownerId\":\"" + OWNER + "\",\"amount\":" + amount
                + ",\"currency\":\"MAD\",\"method\":\"CASH_ON_DELIVERY\"}";
    }

    @Test
    void seedHas5PaymentsForOrders1To5() {
        assertThat(paymentRepository.count()).isGreaterThanOrEqualTo(5);
        assertThat(paymentRepository.findByOrderId(5L)).get().extracting(p -> p.getStatus().name())
                .isEqualTo("PENDING");
    }

    @Test
    void approvedCardThenIdempotentReplayThenConflictOnDifferentParameters() throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-1").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1001, "6780.00", "tok_visa_approved")).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.cardBrand").value("VISA"))
                .andExpect(jsonPath("$.cardLast4").value("4242"));
        long rowsAfterFirstCall = paymentRepository.count();

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-1").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1001, "6780.00", "tok_visa_approved")).with(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(paymentRepository.count()).isEqualTo(rowsAfterFirstCall);

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-1").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1001, "1.00", "tok_visa_approved")).with(customer()))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherKeyForTheSameOrderIsAConflict() throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-2a").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1002, "10.00", "tok_visa_approved")).with(customer()))
                .andExpect(status().isCreated());

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-2b").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1002, "10.00", "tok_visa_approved")).with(customer()))
                .andExpect(status().isConflict());
    }

    @Test
    void declinedCardsAreBusinessResultsWithTheRightReason() throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-3a").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1003, "50.00", "tok_declined_insufficient_funds")).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-3b").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1004, "50.00", "tok_declined_expired_card")).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.failureReason").value("EXPIRED_CARD"));
    }

    @Test
    void unknownTokenReturns400AndStoresNothing() throws Exception {
        long before = paymentRepository.count();

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-4").contentType(MediaType.APPLICATION_JSON)
                        .content(card(1005, "50.00", "tok_does_not_exist")).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown card token"));

        assertThat(paymentRepository.count()).isEqualTo(before);
    }

    @Test
    void randomTokenIsDrawnOnceAndNotRedrawnOnReplay() throws Exception {
        String first = mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-5")
                        .contentType(MediaType.APPLICATION_JSON).content(card(1006, "75.00", "tok_random")).with(customer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        for (int i = 0; i < 5; i++) {
            String replay = mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-5")
                            .contentType(MediaType.APPLICATION_JSON).content(card(1006, "75.00", "tok_random")).with(customer()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));
        }
    }

    @Test
    void cashOnDeliveryIsPendingThenCollectedOnceOnly() throws Exception {
        String created = mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-6")
                        .contentType(MediaType.APPLICATION_JSON).content(cash(1007, "300.00")).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.cardBrand").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        mvc.perform(post("/payments/" + id + "/collect").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.collectedAt").exists());
        mvc.perform(post("/payments/" + id + "/collect").with(customer())).andExpect(status().isConflict());
    }

    @Test
    void collectingACardPaymentIsAConflictAndUnknownIsNotFound() throws Exception {
        mvc.perform(post("/payments/1/collect").with(customer())).andExpect(status().isConflict());
        mvc.perform(post("/payments/987654/collect").with(customer())).andExpect(status().isNotFound());
    }

    @Test
    void validationErrorsAreReportedInTheCommonFormat() throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header("Idempotency-Key", "it-key-7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1008,\"ownerId\":\"" + OWNER
                                + "\",\"amount\":10.00,\"currency\":\"MAD\",\"method\":\"CARD\"}").with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/payments"));
    }

    @Test
    void paymentsCanBeListedAndFilteredByOwner() throws Exception {
        mvc.perform(get("/payments").param("ownerId", OWNER).with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerId").value(OWNER));
        mvc.perform(get("/payments/order/1").with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(1));
        mvc.perform(get("/payments/order/424242").with(customer())).andExpect(status().isNotFound());
    }
}
