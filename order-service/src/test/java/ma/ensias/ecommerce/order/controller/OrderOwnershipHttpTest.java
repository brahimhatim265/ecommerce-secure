package ma.ensias.ecommerce.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderItemResponse;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.exception.NotOwnerException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.security.TestIdentities;
import ma.ensias.ecommerce.order.security.WebSliceSecurityTestConfig;
import ma.ensias.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static ma.ensias.ecommerce.order.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.order.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rules A2, A4 and A6 over HTTP: an order belongs to the user of the token, never to someone else, and the answer for
 * another user's order is the same 404 as for an order that does not exist (decision D2).
 */
@WebMvcTest(OrderController.class)
@Import(WebSliceSecurityTestConfig.class)
class OrderOwnershipHttpTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private static OrderResponse orderOf(UUID owner) {
        return new OrderResponse(6L, owner, OrderStatus.CONFIRMED, new BigDecimal("6780.00"), "MAD",
                "45 Rue Ibnou Katir", "Casablanca", PaymentMethod.CARD, 11L, null,
                List.of(new OrderItemResponse(1L, "PlayStation 5 Slim", new BigDecimal("5990.00"), 1,
                        new BigDecimal("5990.00"))),
                Instant.parse("2026-11-14T10:00:00Z"), Instant.parse("2026-11-14T10:00:00Z"));
    }

    /** Order 6 belongs to {@code owner}; the stub applies the predicate that the controller hands to the service. */
    private void orderSixBelongsTo(UUID owner) {
        when(orderService.getOrder(eq(6L), any())).thenAnswer(invocation -> {
            Predicate<UUID> visibleToCaller = invocation.getArgument(1);
            if (!visibleToCaller.test(owner)) {
                throw new NotOwnerException("Order not found: 6");
            }
            return orderOf(owner);
        });
    }

    private static String validBody() {
        return "{\"shippingAddress\":\"45 Rue Ibnou Katir\",\"shippingCity\":\"Casablanca\","
                + "\"paymentMethod\":\"CASH_ON_DELIVERY\",\"items\":[{\"productId\":1,\"quantity\":1}]}";
    }

    // ------------------------------------------------------------ A2 : one order

    @Test
    void theOwnerReadsTheirOrder() throws Exception {
        orderSixBelongsTo(CUSTOMER_ID);
        mvc.perform(get("/orders/6").with(TestIdentities.customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(CUSTOMER_ID.toString()));
    }

    @Test
    void anotherCustomerGetsA404LikeForAMissingOrder() throws Exception {
        orderSixBelongsTo(CUSTOMER_ID);
        when(orderService.getOrder(eq(7L), any())).thenThrow(new ResourceNotFoundException("Order not found: 7"));

        mvc.perform(get("/orders/6").with(TestIdentities.otherCustomer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found: 6"));
        mvc.perform(get("/orders/7").with(TestIdentities.otherCustomer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found: 7"));
    }

    @Test
    void anAdministratorReadsAnyOrder() throws Exception {
        orderSixBelongsTo(CUSTOMER_ID);
        mvc.perform(get("/orders/6").with(TestIdentities.admin()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ A4 : the list is filtered on the identity

    @Test
    void aCustomerListsOnlyTheirOrdersWhateverTheyAskFor() throws Exception {
        mvc.perform(get("/orders").with(TestIdentities.customer())).andExpect(status().isOk());
        mvc.perform(get("/orders").param("ownerId", CUSTOMER_ID.toString()).with(TestIdentities.customer()))
                .andExpect(status().isOk());
        verify(orderService, org.mockito.Mockito.times(2)).listOrders(CUSTOMER_ID);
    }

    @Test
    void aCustomerCannotListTheOrdersOfSomeoneElse() throws Exception {
        mvc.perform(get("/orders").param("ownerId", OTHER_CUSTOMER_ID.toString()).with(TestIdentities.customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
        verify(orderService, never()).listOrders(any());
    }

    @Test
    void anAdministratorListsEverythingOrTheOrdersOfOneOwner() throws Exception {
        mvc.perform(get("/orders").with(TestIdentities.admin())).andExpect(status().isOk());
        verify(orderService).listOrders(null);
        mvc.perform(get("/orders").param("ownerId", OTHER_CUSTOMER_ID.toString()).with(TestIdentities.admin()))
                .andExpect(status().isOk());
        verify(orderService).listOrders(OTHER_CUSTOMER_ID);
    }

    // ------------------------------------------------------------ A6 : the owner comes from the token

    @Test
    void theOwnerOfANewOrderIsTheUserOfTheToken() throws Exception {
        when(orderService.createOrder(any(UUID.class), any(CreateOrderRequest.class)))
                .thenReturn(orderOf(OTHER_CUSTOMER_ID));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(validBody())
                        .with(TestIdentities.otherCustomer()))
                .andExpect(status().isCreated());
        verify(orderService).createOrder(eq(OTHER_CUSTOMER_ID), any(CreateOrderRequest.class));
    }

    @Test
    void anOwnerIdSentInTheBodyIsRejected() throws Exception {
        String body = "{\"ownerId\":\"" + OTHER_CUSTOMER_ID + "\"," + validBody().substring(1);
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(TestIdentities.customer()))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void anAdministratorCannotPlaceAnOrderOverHttp() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(validBody())
                        .with(TestIdentities.admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
        verify(orderService, never()).createOrder(any(), any());
    }
}
