package ma.ensias.ecommerce.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.dto.CreateOrderRequest;
import ma.ensias.ecommerce.order.dto.OrderItemResponse;
import ma.ensias.ecommerce.order.dto.OrderResponse;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.entity.PaymentMethod;
import ma.ensias.ecommerce.order.exception.BusinessRuleException;
import ma.ensias.ecommerce.order.exception.ExternalServiceException;
import ma.ensias.ecommerce.order.exception.InvalidRequestException;
import ma.ensias.ecommerce.order.exception.ResourceNotFoundException;
import ma.ensias.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private static OrderResponse order(OrderStatus status) {
        return new OrderResponse(6L, OWNER, status, new BigDecimal("6780.00"), "MAD", "45 Rue Ibnou Katir",
                "Casablanca", PaymentMethod.CARD, 11L, null,
                List.of(new OrderItemResponse(1L, "PlayStation 5 Slim", new BigDecimal("5990.00"), 1,
                        new BigDecimal("5990.00"))), Instant.parse("2026-11-14T10:00:00Z"),
                Instant.parse("2026-11-14T10:00:00Z"));
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", OWNER.toString());
        body.put("shippingAddress", "45 Rue Ibnou Katir, Maârif");
        body.put("shippingCity", "Casablanca");
        body.put("paymentMethod", "CARD");
        body.put("cardToken", "tok_visa_approved");
        body.put("items", List.of(item(1, 1), item(2, 1)));
        return body;
    }

    private static Map<String, Object> item(Object productId, Object quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void createReturns201AndPassesTheOwnerIdExplicitlyToTheService() throws Exception {
        when(orderService.createOrder(eq(OWNER), any(CreateOrderRequest.class))).thenReturn(order(OrderStatus.CONFIRMED));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/orders/6"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.paymentId").value(11))
                .andExpect(jsonPath("$.ownerId").value(OWNER.toString()))
                .andExpect(jsonPath("$.items[0].lineTotal").value(5990.00));
        verify(orderService).createOrder(eq(OWNER), any(CreateOrderRequest.class));
    }

    @Test
    void aRefusedPaymentIsStilla201() throws Exception {
        when(orderService.createOrder(eq(OWNER), any(CreateOrderRequest.class)))
                .thenReturn(order(OrderStatus.PAYMENT_REFUSED));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_REFUSED"));
    }

    @Test
    void cashOnDeliveryWithoutTokenIsAccepted() throws Exception {
        Map<String, Object> body = validBody();
        body.put("paymentMethod", "CASH_ON_DELIVERY");
        body.remove("cardToken");
        when(orderService.createOrder(eq(OWNER), any(CreateOrderRequest.class))).thenReturn(order(OrderStatus.CONFIRMED));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("ownerId", null),
                Arguments.of("shippingAddress", ""),
                Arguments.of("shippingAddress", "abc"),
                Arguments.of("shippingAddress", "a".repeat(256)),
                Arguments.of("shippingCity", ""),
                Arguments.of("shippingCity", "c".repeat(61)),
                Arguments.of("paymentMethod", null),
                Arguments.of("cardToken", "Not Valid!"),
                Arguments.of("cardToken", "a".repeat(61)),
                Arguments.of("items", List.of()),
                Arguments.of("items", null));
    }

    @ParameterizedTest(name = "{0} = {1} is rejected")
    @MethodSource("invalidBodies")
    void invalidFieldsReturn400(String field, Object value) throws Exception {
        Map<String, Object> body = validBody();
        body.put(field, value);

        String content = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains(field + ": ");
        verifyNoInteractions(orderService);
    }

    static Stream<Arguments> invalidItems() {
        return Stream.of(
                Arguments.of(item(null, 1), "items[0].productId"),
                Arguments.of(item(0, 1), "items[0].productId"),
                Arguments.of(item(1, null), "items[0].quantity"),
                Arguments.of(item(1, 0), "items[0].quantity"),
                Arguments.of(item(1, 101), "items[0].quantity"));
    }

    @ParameterizedTest(name = "item {0} is rejected")
    @MethodSource("invalidItems")
    void invalidItemsReturn400(Map<String, Object> badItem, String expectedField) throws Exception {
        Map<String, Object> body = validBody();
        body.put("items", List.of(badItem));

        String content = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains(expectedField + ": ");
    }

    @Test
    void moreThan50LinesAreRejected() throws Exception {
        Map<String, Object> body = validBody();
        List<Object> lines = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            lines.add(item(i, 1));
        }
        body.put("items", lines);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardWithoutTokenReturns400WithAnExplicitMessage() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("cardToken");

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.startsWith("cardToken: cardToken is required when paymentMethod is CARD")));
        verifyNoInteractions(orderService);
    }

    @Test
    void cashOnDeliveryWithATokenReturns400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("paymentMethod", "CASH_ON_DELIVERY");

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.containsString("must be absent when paymentMethod is CASH_ON_DELIVERY")));
    }

    @Test
    void malformedOwnerIdMalformedJsonAndUnknownPropertyReturn400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("ownerId", "not-a-uuid");
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());

        body = validBody();
        body.put("username", "hamza");
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Unknown property: username"));
        verifyNoInteractions(orderService);
    }

    @Test
    void serviceExceptionsAreMappedToTheRightStatus() throws Exception {
        when(orderService.createOrder(eq(OWNER), any(CreateOrderRequest.class)))
                .thenThrow(new ResourceNotFoundException("User not found"))
                .thenThrow(new BusinessRuleException("User account is inactive"))
                .thenThrow(new InvalidRequestException("Unknown card token"))
                .thenThrow(new ExternalServiceException("payment-service is unreachable or timed out",
                        List.of("orderId: 6")))
                .thenThrow(new OptimisticLockingFailureException("collision"));

        int[] expected = {404, 422, 400, 503, 409};
        for (int expectedStatus : expected) {
            mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(jsonPath("$.status").value(expectedStatus))
                    .andExpect(jsonPath("$.path").value("/orders"));
        }
    }

    @Test
    void serviceUnavailableCarriesTheOrderIdInTheDetails() throws Exception {
        when(orderService.createOrder(eq(OWNER), any(CreateOrderRequest.class)))
                .thenThrow(new ExternalServiceException("payment-service is unreachable or timed out",
                        List.of("orderId: 6")));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details[0]").value("orderId: 6"));
    }

    @Test
    void getAndListOrders() throws Exception {
        when(orderService.getOrder(6L)).thenReturn(order(OrderStatus.CONFIRMED));
        when(orderService.listOrders(OWNER)).thenReturn(List.of(order(OrderStatus.CONFIRMED)));
        when(orderService.getOrder(99L)).thenThrow(new ResourceNotFoundException("Order not found: 99"));

        mvc.perform(get("/orders/6")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(6));
        mvc.perform(get("/orders").param("ownerId", OWNER.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/orders/99")).andExpect(status().isNotFound());
        mvc.perform(get("/orders/abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/orders").param("ownerId", "xyz")).andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorsAreHidden() throws Exception {
        when(orderService.getOrder(6L)).thenThrow(new IllegalStateException("select * from orders secret"));

        String content = mvc.perform(get("/orders/6")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("select").doesNotContain("secret");
    }
}
