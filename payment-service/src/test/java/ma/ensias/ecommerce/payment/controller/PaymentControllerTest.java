package ma.ensias.ecommerce.payment.controller;

import ma.ensias.ecommerce.payment.security.mtls.ClientCertificate;
import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.payment.dto.CreatePaymentRequest;
import ma.ensias.ecommerce.payment.dto.PaymentCreationResult;
import ma.ensias.ecommerce.payment.dto.PaymentResponse;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import ma.ensias.ecommerce.payment.exception.DuplicateResourceException;
import ma.ensias.ecommerce.payment.exception.InvalidPaymentStateException;
import ma.ensias.ecommerce.payment.exception.InvalidRequestException;
import ma.ensias.ecommerce.payment.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.payment.service.PaymentService;
import ma.ensias.ecommerce.payment.security.WebSliceSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static ma.ensias.ecommerce.payment.security.TestJwtSupport.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(WebSliceSecurityTestConfig.class)
class PaymentControllerTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String KEY_HEADER = "Idempotency-Key";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentService paymentService;

    private static PaymentResponse payment(PaymentStatus status) {
        return new PaymentResponse(7L, "PAY-ABCDE12345", 42L, OWNER, new BigDecimal("6780.00"), "MAD",
                PaymentMethod.CARD, status, null, "VISA", "4242", Instant.parse("2026-11-14T10:00:00Z"),
                Instant.parse("2026-11-14T10:00:00Z"), null);
    }

    private static Map<String, Object> validCardRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", 42);
        body.put("ownerId", OWNER.toString());
        body.put("amount", new BigDecimal("6780.00"));
        body.put("currency", "MAD");
        body.put("method", "CARD");
        body.put("cardToken", "tok_visa_approved");
        return body;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void newPaymentReturns201WithLocation() throws Exception {
        when(paymentService.createPayment(any(CreatePaymentRequest.class), eq("order-42")))
                .thenReturn(new PaymentCreationResult(payment(PaymentStatus.SUCCEEDED), false));

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/payments/7"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.currency").value("MAD"))
                .andExpect(jsonPath("$.amount").value(6780.00));
    }

    @Test
    void failedPaymentIsStillA201() throws Exception {
        when(paymentService.createPayment(any(CreatePaymentRequest.class), eq("order-42")))
                .thenReturn(new PaymentCreationResult(payment(PaymentStatus.FAILED), false));

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void replayReturns200WithTheReplayHeader() throws Exception {
        when(paymentService.createPayment(any(CreatePaymentRequest.class), eq("order-42")))
                .thenReturn(new PaymentCreationResult(payment(PaymentStatus.SUCCEEDED), true));

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void cashOnDeliveryWithoutTokenIsAccepted() throws Exception {
        Map<String, Object> body = validCardRequest();
        body.put("method", "CASH_ON_DELIVERY");
        body.remove("cardToken");
        when(paymentService.createPayment(any(CreatePaymentRequest.class), eq("order-42")))
                .thenReturn(new PaymentCreationResult(payment(PaymentStatus.PENDING), false));

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(customer()))
                .andExpect(status().isCreated());
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).contentType(MediaType.APPLICATION_JSON).content(json(validCardRequest())).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]").value("Idempotency-Key: header is required"));
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "Idempotency-Key ''{0}'' is rejected")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"has space", "bad/slash", "quote\"", "é-accent"})
    void invalidIdempotencyKeyReturns400(String key) throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void tooLongIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "k".repeat(65)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isBadRequest());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("orderId", null),
                Arguments.of("orderId", 0),
                Arguments.of("orderId", -3),
                Arguments.of("ownerId", null),
                Arguments.of("amount", null),
                Arguments.of("amount", new BigDecimal("0.00")),
                Arguments.of("amount", new BigDecimal("-5.00")),
                Arguments.of("amount", new BigDecimal("10.123")),
                Arguments.of("amount", new BigDecimal("12345678901.00")),
                Arguments.of("currency", "USD"),
                Arguments.of("currency", ""),
                Arguments.of("currency", null),
                Arguments.of("method", null),
                Arguments.of("cardToken", "TOK-UPPER"),
                Arguments.of("cardToken", "a".repeat(61)));
    }

    @ParameterizedTest(name = "{0} = {1} is rejected")
    @MethodSource("invalidBodies")
    void invalidBodyFieldsReturn400(String field, Object value) throws Exception {
        Map<String, Object> body = validCardRequest();
        body.put(field, value);

        String content = mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains(field + ": ");
        verifyNoInteractions(paymentService);
    }

    @Test
    void cardWithoutTokenReturns400WithAnExplicitMessage() throws Exception {
        Map<String, Object> body = validCardRequest();
        body.remove("cardToken");

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.startsWith("cardToken: cardToken is required when method is CARD")));
    }

    @Test
    void cashOnDeliveryWithATokenReturns400() throws Exception {
        Map<String, Object> body = validCardRequest();
        body.put("method", "CASH_ON_DELIVERY");

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.containsString("must be absent when method is CASH_ON_DELIVERY")));
    }

    @Test
    void unknownPropertyAndMalformedJsonReturn400() throws Exception {
        Map<String, Object> body = validCardRequest();
        body.put("cardNumber", "4242424242424242");

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Unknown property: cardNumber"));
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content("{oops").with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEnumValueAndMalformedOwnerUuidReturn400() throws Exception {
        Map<String, Object> body = validCardRequest();
        body.put("method", "BITCOIN");
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(customer()))
                .andExpect(status().isBadRequest());

        body = validCardRequest();
        body.put("ownerId", "not-a-uuid");
        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)).with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTokenFromTheServiceReturns400() throws Exception {
        when(paymentService.createPayment(any(CreatePaymentRequest.class), eq("order-42")))
                .thenThrow(new InvalidRequestException("Unknown card token"));

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown card token"));
    }

    @Test
    void idempotencyConflictReturns409() throws Exception {
        when(paymentService.createPayment(any(CreatePaymentRequest.class), eq("order-42")))
                .thenThrow(new DuplicateResourceException("Idempotency-Key already used with different parameters"));

        mvc.perform(post("/payments").with(ClientCertificate.orderService()).header(KEY_HEADER, "order-42").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCardRequest())).with(customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void getByIdAndByOrderAndList() throws Exception {
        when(paymentService.getPayment(7L)).thenReturn(payment(PaymentStatus.SUCCEEDED));
        when(paymentService.getPaymentByOrder(42L)).thenReturn(payment(PaymentStatus.SUCCEEDED));
        when(paymentService.listPayments(OWNER)).thenReturn(List.of(payment(PaymentStatus.SUCCEEDED)));

        mvc.perform(get("/payments/7").with(customer())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7));
        mvc.perform(get("/payments/order/42").with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(42));
        mvc.perform(get("/payments").param("ownerId", OWNER.toString()).with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unknownPaymentReturns404AndMalformedIdReturns400() throws Exception {
        when(paymentService.getPayment(99L)).thenThrow(new ResourceNotFoundException("Payment not found: 99"));

        mvc.perform(get("/payments/99").with(customer())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/payments/abc").with(customer())).andExpect(status().isBadRequest());
        mvc.perform(get("/payments").param("ownerId", "xyz").with(customer())).andExpect(status().isBadRequest());
    }

    @Test
    void collectReturns200Then409ThenNotFound() throws Exception {
        when(paymentService.collect(7L)).thenReturn(payment(PaymentStatus.SUCCEEDED));
        when(paymentService.collect(8L)).thenThrow(new InvalidPaymentStateException("not collectable"));
        when(paymentService.collect(9L)).thenThrow(new ResourceNotFoundException("Payment not found: 9"));

        mvc.perform(post("/payments/7/collect").with(customer())).andExpect(status().isOk());
        mvc.perform(post("/payments/8/collect").with(customer())).andExpect(status().isConflict());
        mvc.perform(post("/payments/9/collect").with(customer())).andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoRefundEndpoint() throws Exception {
        mvc.perform(post("/payments/7/refund").with(customer())).andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorsAreHidden() throws Exception {
        when(paymentService.getPayment(7L)).thenThrow(new IllegalStateException("jdbc:h2 secret"));

        String content = mvc.perform(get("/payments/7").with(customer())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("jdbc").doesNotContain("secret");
    }
}
