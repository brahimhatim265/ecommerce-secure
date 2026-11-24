package ma.ensias.ecommerce.user.controller;

import ma.ensias.ecommerce.user.security.mtls.ClientCertificate;
import ma.ensias.ecommerce.user.service.AuthenticationService;
import ma.ensias.ecommerce.user.service.UserService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ma.ensias.ecommerce.user.security.TestIdentities;
import ma.ensias.ecommerce.user.security.WebSliceSecurityTestConfig;
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

import static ma.ensias.ecommerce.user.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.OTHER_CUSTOMER_ID;
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
@WebMvcTest({UserController.class, AuthController.class})
@Import(WebSliceSecurityTestConfig.class)
class AuditTrailHttpTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger audit = (Logger) LoggerFactory.getLogger("AUDIT");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthenticationService authenticationService;


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

    @Test
    void anAnonymousCallerIsRefusedForMissingToken() throws Exception {
        mvc.perform(get("/users")).andExpect(status().isUnauthorized());

        assertThat(lines()).containsExactly(line("DENY", "missing_token", "GET", "/users", null, "-"));
    }

    @Test
    void aTokenThatCannotBeValidatedIsRefusedAsInvalid() throws Exception {
        mvc.perform(get("/users").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.token"))
                .andExpect(status().isUnauthorized());

        assertThat(lines()).containsExactly(line("DENY", "invalid_token", "GET", "/users", null, "-"));
    }

    @Test
    void aCustomerOnAnAdministrationEndpointIsRefusedForItsRole() throws Exception {
        mvc.perform(get("/users").with(TestIdentities.customer())).andExpect(status().isForbidden());

        assertThat(lines()).containsExactly(line("DENY", "insufficient_role", "GET", "/users", CUSTOMER_ID, "-"));
    }

    @Test
    void aCustomerOnTheProfileOfSomeoneElseIsRefusedAsNotTheOwner() throws Exception {
        mvc.perform(get("/users/" + OTHER_CUSTOMER_ID).with(TestIdentities.customer()))
                .andExpect(status().isForbidden());

        assertThat(lines()).containsExactly(
                line("DENY", "not_owner", "GET", "/users/" + OTHER_CUSTOMER_ID, CUSTOMER_ID, "-"));
    }

    @Test
    void anAllowedAdministratorIsWrittenWithoutTheQueryString() throws Exception {
        mvc.perform(get("/users").param("email", "hamza.belhaj@example.com").with(TestIdentities.admin()))
                .andExpect(status().isOk());

        assertThat(lines()).containsExactly(line("ALLOW", "-", "GET", "/users", ADMIN_ID, "-"));
        assertThat(lines().get(0)).doesNotContain("@").doesNotContain("email");
    }

    @Test
    void theInternalEndpointWithoutCertificateIsRefusedForMissingCertificate() throws Exception {
        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(lines()).containsExactly(
                line("DENY", "missing_certificate", "POST", "/internal/auth/verify", null, "-"));
    }

    @Test
    void theInternalEndpointWithTheCertificateOfAnotherServiceIsRefusedWithItsName() throws Exception {
        mvc.perform(post("/internal/auth/verify").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(ClientCertificate.of("order-service")))
                .andExpect(status().isForbidden());

        assertThat(lines()).containsExactly(
                line("DENY", "service_not_allowed", "POST", "/internal/auth/verify", null, "order-service"));
    }
}
