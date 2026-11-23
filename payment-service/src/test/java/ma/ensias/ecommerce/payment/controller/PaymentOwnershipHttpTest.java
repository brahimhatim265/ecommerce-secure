package ma.ensias.ecommerce.payment.controller;

import ma.ensias.ecommerce.payment.dto.PaymentResponse;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import ma.ensias.ecommerce.payment.exception.NotOwnerException;
import ma.ensias.ecommerce.payment.security.TestIdentities;
import ma.ensias.ecommerce.payment.security.WebSliceSecurityTestConfig;
import ma.ensias.ecommerce.payment.security.mtls.ClientCertificate;
import ma.ensias.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

import static ma.ensias.ecommerce.payment.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.payment.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rules A3, A4 and A5 over HTTP: a payment belongs to the user of the token, the answer for another user's payment
 * is a 404 identical to a missing payment (decision D2), and order-service cannot pay for somebody else.
 */
@WebMvcTest(PaymentController.class)
@Import(WebSliceSecurityTestConfig.class)
class PaymentOwnershipHttpTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;

    private static PaymentResponse paymentOf(UUID owner) {
        return new PaymentResponse(7L, "PAY-ABCDE12345", 42L, owner, new BigDecimal("6780.00"), "MAD",
                PaymentMethod.CARD, PaymentStatus.SUCCEEDED, null, "VISA", "4242",
                Instant.parse("2026-11-14T10:00:00Z"), Instant.parse("2026-11-14T10:00:00Z"), null);
    }

    private void paymentSevenBelongsTo(UUID owner) {
        when(paymentService.getPayment(eq(7L), any())).thenAnswer(invocation -> {
            Predicate<UUID> visibleToCaller = invocation.getArgument(1);
            if (!visibleToCaller.test(owner)) {
                throw new NotOwnerException("Payment not found: 7");
            }
            return paymentOf(owner);
        });
        when(paymentService.getPaymentByOrder(eq(42L), any())).thenAnswer(invocation -> {
            Predicate<UUID> visibleToCaller = invocation.getArgument(1);
            if (!visibleToCaller.test(owner)) {
                throw new NotOwnerException("No payment found for order 42");
            }
            return paymentOf(owner);
        });
    }

    // ------------------------------------------------------------ A3 : one payment

    @Test
    void theOwnerReadsTheirPayment() throws Exception {
        paymentSevenBelongsTo(CUSTOMER_ID);
        mvc.perform(get("/payments/7").with(TestIdentities.customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(CUSTOMER_ID.toString()));
        mvc.perform(get("/payments/order/42").with(TestIdentities.customer()))
                .andExpect(status().isOk());
    }

    @Test
    void anotherCustomerGetsA404LikeForAMissingPayment() throws Exception {
        paymentSevenBelongsTo(CUSTOMER_ID);
        mvc.perform(get("/payments/7").with(TestIdentities.otherCustomer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found: 7"));
        mvc.perform(get("/payments/order/42").with(TestIdentities.otherCustomer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No payment found for order 42"));
    }

    @Test
    void anAdministratorReadsAnyPayment() throws Exception {
        paymentSevenBelongsTo(CUSTOMER_ID);
        mvc.perform(get("/payments/7").with(TestIdentities.admin())).andExpect(status().isOk());
        mvc.perform(get("/payments/order/42").with(TestIdentities.admin())).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ A4 : the list is filtered on the identity

    @Test
    void aCustomerListsOnlyTheirPaymentsWhateverTheyAskFor() throws Exception {
        mvc.perform(get("/payments").with(TestIdentities.customer())).andExpect(status().isOk());
        mvc.perform(get("/payments").param("ownerId", CUSTOMER_ID.toString()).with(TestIdentities.customer()))
                .andExpect(status().isOk());
        verify(paymentService, org.mockito.Mockito.times(2)).listPayments(CUSTOMER_ID);
    }

    @Test
    void aCustomerCannotListThePaymentsOfSomeoneElse() throws Exception {
        mvc.perform(get("/payments").param("ownerId", OTHER_CUSTOMER_ID.toString()).with(TestIdentities.customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
        verify(paymentService, never()).listPayments(any());
    }

    @Test
    void anAdministratorListsEverythingOrThePaymentsOfOneOwner() throws Exception {
        mvc.perform(get("/payments").with(TestIdentities.admin())).andExpect(status().isOk());
        verify(paymentService).listPayments(null);
        mvc.perform(get("/payments").param("ownerId", OTHER_CUSTOMER_ID.toString()).with(TestIdentities.admin()))
                .andExpect(status().isOk());
        verify(paymentService).listPayments(OTHER_CUSTOMER_ID);
    }

    // ------------------------------------------------------------ A5 : order-service pays for the user of the token only

    @Test
    void theOrderServiceCannotCreateAPaymentForAnotherUserThanTheRelayedOne() throws Exception {
        String body = "{\"orderId\":42,\"ownerId\":\"" + OTHER_CUSTOMER_ID + "\",\"amount\":6780.00,"
                + "\"currency\":\"MAD\",\"method\":\"CASH_ON_DELIVERY\"}";
        mvc.perform(post("/payments").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(ClientCertificate.orderService()).with(TestIdentities.customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
        verifyNoInteractions(paymentService);
    }
}
