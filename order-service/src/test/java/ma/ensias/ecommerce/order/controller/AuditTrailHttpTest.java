package ma.ensias.ecommerce.order.controller;

import ma.ensias.ecommerce.order.exception.NotOwnerException;
import ma.ensias.ecommerce.order.service.OrderService;
import ma.ensias.ecommerce.order.service.ProductService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ma.ensias.ecommerce.order.security.TestIdentities;
import ma.ensias.ecommerce.order.security.WebSliceSecurityTestConfig;
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

import static ma.ensias.ecommerce.order.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.order.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.order.security.TestIdentities.OTHER_CUSTOMER_ID;
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
@WebMvcTest({OrderController.class, ProductController.class})
@Import(WebSliceSecurityTestConfig.class)
class AuditTrailHttpTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger audit = (Logger) LoggerFactory.getLogger("AUDIT");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private ProductService productService;


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

    private static String validBody() {
        return "{\"shippingAddress\":\"45 Rue Ibnou Katir\",\"shippingCity\":\"Casablanca\","
                + "\"paymentMethod\":\"CASH_ON_DELIVERY\",\"items\":[{\"productId\":1,\"quantity\":1}]}";
    }

    @Test
    void anAnonymousCallerIsRefusedForMissingToken() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());

        assertThat(lines()).containsExactly(line("DENY", "missing_token", "POST", "/orders", null, "-"));
    }

    @Test
    void anAdministratorPlacingAnOrderIsRefusedForItsRole() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(validBody())
                        .with(TestIdentities.admin()))
                .andExpect(status().isForbidden());

        assertThat(lines()).containsExactly(line("DENY", "insufficient_role", "POST", "/orders", ADMIN_ID, "-"));
    }

    @Test
    void anotherCustomerOnAnOrderIsWrittenAsNotTheOwnerEvenThoughTheAnswerIsA404() throws Exception {
        when(orderService.getOrder(eq(6L), any())).thenThrow(new NotOwnerException("Order not found: 6"));

        mvc.perform(get("/orders/6").with(TestIdentities.otherCustomer())).andExpect(status().isNotFound());

        assertThat(lines()).containsExactly(line("DENY", "not_owner", "GET", "/orders/6", OTHER_CUSTOMER_ID, "-"));
    }

    @Test
    void listingTheOrdersOfSomeoneElseIsRefusedAsNotTheOwner() throws Exception {
        mvc.perform(get("/orders").param("ownerId", OTHER_CUSTOMER_ID.toString()).with(TestIdentities.customer()))
                .andExpect(status().isForbidden());

        assertThat(lines()).containsExactly(line("DENY", "not_owner", "GET", "/orders", CUSTOMER_ID, "-"));
    }

    @Test
    void aCustomerListingTheirOrdersIsAllowed() throws Exception {
        mvc.perform(get("/orders").with(TestIdentities.customer())).andExpect(status().isOk());

        assertThat(lines()).containsExactly(line("ALLOW", "-", "GET", "/orders", CUSTOMER_ID, "-"));
    }

    @Test
    void thePublicCatalogIsAllowedWithoutUser() throws Exception {
        mvc.perform(get("/products")).andReturn();

        assertThat(lines()).containsExactly(line("ALLOW", "-", "GET", "/products", null, "-"));
    }
}
