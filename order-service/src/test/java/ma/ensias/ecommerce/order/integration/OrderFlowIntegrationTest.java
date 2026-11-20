package ma.ensias.ecommerce.order.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.ensias.ecommerce.order.entity.Order;
import ma.ensias.ecommerce.order.entity.OrderStatus;
import ma.ensias.ecommerce.order.repository.OrderRepository;
import ma.ensias.ecommerce.order.repository.ProductRepository;
import ma.ensias.ecommerce.order.security.TestJwtDecoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static ma.ensias.ecommerce.order.security.TestJwtSupport.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full order flow with the real database and services, and user-service / payment-service replaced by a mock
 * HTTP server (the production RestClient beans are used, only their request factory is swapped).
 * Each test gets a fresh context, hence a fresh seeded database (new orders get id 6).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders-it-${random.uuid};DB_CLOSE_DELAY=-1",
        "services.user.base-url=http://user-service.test",
        "services.payment.base-url=http://payment-service.test"})
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderFlowIntegrationTest {

    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String USER_URL = "http://user-service.test/users/" + OWNER;
    private static final String PAYMENT_URL = "http://payment-service.test/payments";
    private static final String ACTIVE_USER = "{\"id\":\"" + OWNER + "\",\"email\":\"hamza.belhaj@example.com\","
            + "\"roles\":[\"CUSTOMER\"],\"active\":true}";

    @TestConfiguration
    static class MockHttpConfig {

        private final RestTemplate restTemplate = new RestTemplate();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        @Bean
        @Primary
        ClientHttpRequestFactory mockRequestFactory() {
            return restTemplate.getRequestFactory();
        }

        @Bean
        MockRestServiceServer mockRestServiceServer() {
            return server;
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MockRestServiceServer server;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private static String orderBody(String method, String token, String items) {
        return "{\"ownerId\":\"" + OWNER + "\",\"shippingAddress\":\"45 Rue Ibnou Katir, Maârif\","
                + "\"shippingCity\":\"Casablanca\",\"paymentMethod\":\"" + method + "\""
                + (token == null ? "" : ",\"cardToken\":\"" + token + "\"") + ",\"items\":" + items + "}";
    }

    private static final String PS5_AND_CONTROLLER = "[{\"productId\":1,\"quantity\":1},{\"productId\":2,\"quantity\":1}]";

    private int stock(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getStock();
    }

    private void expectActiveUser() {
        server.expect(ExpectedCount.once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ACTIVE_USER, MediaType.APPLICATION_JSON));
    }

    private void expectPayment(String body, HttpStatus status) {
        server.expect(ExpectedCount.once(), requestTo(PAYMENT_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order-6"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @Test
    void seededIdsAreStable() {
        assertThat(productRepository.findBySku("PS5-SLIM-STD").orElseThrow().getId()).isEqualTo(1L);
        assertThat(productRepository.findBySku("PS5-DUALSENSE").orElseThrow().getId()).isEqualTo(2L);
        assertThat(orderRepository.findAll()).extracting(Order::getId).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void successfulCardOrderIsConfirmedAndStockStaysReserved() throws Exception {
        expectActiveUser();
        server.expect(ExpectedCount.once(), requestTo(PAYMENT_URL)).andExpect(header("Idempotency-Key", "order-6"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.orderId").value(6))
                .andExpect(MockRestRequestMatchers.jsonPath("$.ownerId").value(OWNER))
                .andExpect(MockRestRequestMatchers.jsonPath("$.amount").value(6780.0))
                .andExpect(MockRestRequestMatchers.jsonPath("$.currency").value("MAD"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.method").value("CARD"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.cardToken").value("tok_visa_approved"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":6,\"reference\":\"PAY-ABC\",\"orderId\":6,\"status\":\"SUCCEEDED\"}"));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(6))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(6))
                .andExpect(jsonPath("$.totalAmount").value(6780.0))
                .andExpect(jsonPath("$.currency").value("MAD"))
                .andExpect(jsonPath("$.items.length()").value(2));

        assertThat(stock("PS5-SLIM-STD")).isEqualTo(17);
        assertThat(stock("PS5-DUALSENSE")).isEqualTo(58);
        server.verify();
    }

    @Test
    void refusedCardGives201WithPaymentRefusedAndRestoresTheStock() throws Exception {
        expectActiveUser();
        expectPayment("{\"id\":7,\"status\":\"FAILED\",\"failureReason\":\"INSUFFICIENT_FUNDS\"}",
                HttpStatus.CREATED);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_declined_insufficient_funds", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_REFUSED"))
                .andExpect(jsonPath("$.paymentFailureReason").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.paymentId").value(7));

        assertThat(stock("PS5-SLIM-STD")).isEqualTo(18);
        assertThat(stock("PS5-DUALSENSE")).isEqualTo(59);
        server.verify();
    }

    @Test
    void cashOnDeliveryOrderIsConfirmedWithAPendingPayment() throws Exception {
        expectActiveUser();
        expectPayment("{\"id\":8,\"status\":\"PENDING\"}", HttpStatus.CREATED);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CASH_ON_DELIVERY", null, PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH_ON_DELIVERY"));
        server.verify();
    }

    @Test
    void ioErrorOnThePaymentCallIsRetriedOnceWithTheSameKey() throws Exception {
        expectActiveUser();
        server.expect(ExpectedCount.once(), requestTo(PAYMENT_URL)).andExpect(header("Idempotency-Key", "order-6"))
                .andRespond(withException(new IOException("connection reset")));
        expectPayment("{\"id\":6,\"status\":\"SUCCEEDED\"}", HttpStatus.OK);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        server.verify();
    }

    @Test
    void persistentPaymentOutageGives503PaymentErrorAndRestoredStock() throws Exception {
        expectActiveUser();
        server.expect(ExpectedCount.times(2), requestTo(PAYMENT_URL))
                .andRespond(withException(new IOException("down")));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.details[0]").value("orderId: 6"));

        assertThat(orderRepository.findById(6L).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_ERROR);
        assertThat(stock("PS5-SLIM-STD")).isEqualTo(18);
        assertThat(stock("PS5-DUALSENSE")).isEqualTo(59);
        server.verify();
    }

    @Test
    void paymentServiceError500Gives503AndRestoresTheStock() throws Exception {
        expectActiveUser();
        expectPayment("{}", HttpStatus.INTERNAL_SERVER_ERROR);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isServiceUnavailable());

        assertThat(orderRepository.findById(6L).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_ERROR);
        assertThat(stock("PS5-SLIM-STD")).isEqualTo(18);
    }

    @Test
    void unknownTokenRejectedByPaymentServiceGives400AndRestoresTheStock() throws Exception {
        expectActiveUser();
        expectPayment("{\"status\":400,\"message\":\"Unknown card token\",\"details\":[]}", HttpStatus.BAD_REQUEST);

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_nope", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown card token"));

        assertThat(orderRepository.findById(6L).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_ERROR);
        assertThat(stock("PS5-SLIM-STD")).isEqualTo(18);
        server.verify();
    }

    @Test
    void insufficientStockGives422WithoutCallingPaymentService() throws Exception {
        expectActiveUser();

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", "[{\"productId\":6,\"quantity\":9}]")).with(customer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("MacBook Air 13")));

        assertThat(stock("MBA13-M4")).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(5);
        server.verify();
    }

    @Test
    void unknownProductGives404AndNoOrderIsCreated() throws Exception {
        expectActiveUser();

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", "[{\"productId\":999,\"quantity\":1}]")).with(customer()))
                .andExpect(status().isNotFound());

        assertThat(orderRepository.count()).isEqualTo(5);
    }

    @Test
    void unknownUserGives404() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(USER_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isNotFound());

        assertThat(stock("PS5-SLIM-STD")).isEqualTo(18);
        server.verify();
    }

    @Test
    void inactiveUserGives422() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(USER_URL)).andRespond(withSuccess(
                "{\"id\":\"" + OWNER + "\",\"active\":false}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isUnprocessableEntity());
        server.verify();
    }

    @Test
    void userServiceOutageGives503AndNothingIsReserved() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(USER_URL)).andRespond(withException(new IOException("down")));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isServiceUnavailable());

        assertThat(stock("PS5-SLIM-STD")).isEqualTo(18);
        assertThat(orderRepository.count()).isEqualTo(5);
    }

    @Test
    void duplicateLinesGive400WithoutAnyRemoteCall() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved",
                                "[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]")).with(customer()))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test
    void validationErrorsMakeNoRemoteCall() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", null, PS5_AND_CONTROLLER)).with(customer()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("CARD", "tok_visa_approved", "[]")).with(customer()))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test
    void seededOrdersAreReadableAndFilteredByOwner() throws Exception {
        mvc.perform(get("/orders/1").with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(6780.0))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/orders").param("ownerId", OWNER).with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/orders/9999").with(customer())).andExpect(status().isNotFound());
    }

    @Test
    void productCatalogIsServedWithFilterAndCrud() throws Exception {
        mvc.perform(get("/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9));
        mvc.perform(get("/products").param("category", "gaming")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        String created = mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NEW-1\",\"name\":\"Nouveau produit\",\"brand\":\"Marque\","
                                + "\"category\":\"Gaming\",\"price\":99.9,\"stock\":5}").with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(99.90))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NEW-1\",\"name\":\"Autre nom\",\"brand\":\"Marque\","
                                + "\"category\":\"Gaming\",\"price\":99.9,\"stock\":5}").with(customer()))
                .andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/products/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NEW-1\",\"name\":\"Nouveau produit v2\",\"brand\":\"Marque\","
                                + "\"category\":\"Gaming\",\"price\":120,\"stock\":7}").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(7));
    }
}
