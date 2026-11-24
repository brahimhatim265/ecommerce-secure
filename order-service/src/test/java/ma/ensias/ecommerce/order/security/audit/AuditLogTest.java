package ma.ensias.ecommerce.order.security.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ma.ensias.ecommerce.order.security.AccessRules;
import ma.ensias.ecommerce.order.security.JwtRolesConfig;
import ma.ensias.ecommerce.order.security.TestIdentities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static ma.ensias.ecommerce.order.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.order.security.TestIdentities.OTHER_CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuditLogTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger audit = (Logger) LoggerFactory.getLogger("AUDIT");

    @BeforeEach
    void capture() {
        audit.setLevel(Level.INFO);
        logs.start();
        audit.addAppender(logs);
    }

    @AfterEach
    void release() {
        audit.detachAppender(logs);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private ILoggingEvent onlyEvent() {
        assertThat(logs.list).hasSize(1);
        return logs.list.get(0);
    }

    private String onlyLine() {
        return onlyEvent().getFormattedMessage();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static void signedInAs(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    // ------------------------------------------------------------ the format

    @Test
    void anAllowedAnonymousRequestIsAnInfoLineWithNoUserAndNoCaller() {
        AuditLog.allow(request("GET", "/products"));

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
        assertThat(onlyLine()).isEqualTo("AUDIT decision=ALLOW reason=- method=GET path=/products user=- caller=-");
    }

    @Test
    void aRefusalIsAWarnLineWithItsReason() {
        AuditLog.deny(request("GET", "/orders/42"), AuditLog.NOT_OWNER);

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
        assertThat(onlyLine())
                .isEqualTo("AUDIT decision=DENY reason=not_owner method=GET path=/orders/42 user=- caller=-");
    }

    @Test
    void theUserIsTheSubjectOfTheToken() {
        signedInAs(TestIdentities.authentication(CUSTOMER_ID, "CUSTOMER"));

        AuditLog.allow(request("GET", "/orders"));

        assertThat(onlyLine()).contains("user=" + CUSTOMER_ID);
    }

    @Test
    void anEmailOrAnyOtherSubjectIsNeverWritten() {
        Jwt jwt = Jwt.withTokenValue("token-value-for-tests").header("alg", "RS256")
                .subject("hamza.belhaj@example.com").claim("roles", List.of("CUSTOMER")).build();
        signedInAs(new JwtRolesConfig().jwtAuthenticationConverter().convert(jwt));

        AuditLog.allow(request("GET", "/orders"));

        assertThat(onlyLine()).contains("user=-").doesNotContain("@").doesNotContain("hamza");
    }

    @Test
    void anAnonymousAuthenticationHasNoUser() {
        signedInAs(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        AuditLog.allow(request("GET", "/products"));

        assertThat(onlyLine()).contains("user=-");
    }

    // ------------------------------------------------------------ what is never written

    @Test
    void theQueryStringIsNeverWritten() {
        MockHttpServletRequest request = request("GET", "/users");
        request.setQueryString("email=hamza.belhaj@example.com");

        AuditLog.allow(request);

        assertThat(onlyLine()).contains("path=/users").doesNotContain("@").doesNotContain("email");
    }

    @Test
    void theTokenAndTheAuthorizationHeaderAreNeverWritten() {
        MockHttpServletRequest request = request("POST", "/orders");
        request.addHeader("Authorization", "Bearer SECRET-TOKEN-VALUE");

        AuditLog.deny(request, AuditLog.INVALID_TOKEN);

        assertThat(onlyLine()).doesNotContain("SECRET").doesNotContain("Bearer").doesNotContain("Authorization");
    }

    @Test
    void aCraftedUrlCannotForgeASecondLine() {
        AuditLog.deny(request("GET", "/a\r\nAUDIT decision=ALLOW reason=- fake"), AuditLog.MISSING_TOKEN);

        String line = onlyLine();
        assertThat(line).doesNotContain("\r").doesNotContain("\n");
        assertThat(line.split(" ")).hasSize(7); // AUDIT + decision, reason, method, path, user, caller
    }

    @Test
    void aVeryLongPathIsCut() {
        AuditLog.allow(request("GET", "/" + "a".repeat(5000)));

        assertThat(onlyLine().length()).isLessThan(400);
    }

    // ------------------------------------------------------------ one line per request

    @Test
    void theFirstDecisionOfARequestWins() {
        MockHttpServletRequest request = request("GET", "/orders/42");

        AuditLog.deny(request, AuditLog.NOT_OWNER);
        AuditLog.deny(request, AuditLog.INSUFFICIENT_ROLE);
        AuditLog.allow(request);

        assertThat(onlyLine()).contains("decision=DENY", "reason=not_owner");
    }

    @Test
    void aRequestThatWasServedIsWrittenOnce() {
        MockHttpServletRequest request = request("GET", "/orders");

        AuditLog.allow(request);
        AuditLog.allow(request);

        assertThat(logs.list).hasSize(1);
    }

    // ------------------------------------------------------------ the reason of an authorization refusal

    @Test
    void aRefusalWithoutHintIsAnInsufficientRole() {
        assertThat(AuditLog.denialReasonOf(request("GET", "/users"))).isEqualTo(AuditLog.INSUFFICIENT_ROLE);
    }

    @Test
    void anIdentifiedUserWhoIsNotTheOwnerLeavesANotOwnerHint() {
        MockHttpServletRequest request = request("GET", "/users/x");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new AccessRules().isOwner(TestIdentities.authentication(CUSTOMER_ID, "CUSTOMER"), OTHER_CUSTOMER_ID);

        assertThat(AuditLog.denialReasonOf(request)).isEqualTo(AuditLog.NOT_OWNER);
    }

    @Test
    void theOwnerLeavesNoHint() {
        MockHttpServletRequest request = request("GET", "/users/x");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new AccessRules().isOwner(TestIdentities.authentication(CUSTOMER_ID, "CUSTOMER"), CUSTOMER_ID);

        assertThat(AuditLog.denialReasonOf(request)).isEqualTo(AuditLog.INSUFFICIENT_ROLE);
    }

    @Test
    void aCallerWithoutIdentityLeavesNoHint() {
        MockHttpServletRequest request = request("GET", "/users/x");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new AccessRules().isOwner(null, OTHER_CUSTOMER_ID);

        assertThat(AuditLog.denialReasonOf(request)).isEqualTo(AuditLog.INSUFFICIENT_ROLE);
    }

    @Test
    void theHintIsIgnoredOutsideOfARequest() {
        assertThatCode(() -> new AccessRules()
                .isOwner(TestIdentities.authentication(CUSTOMER_ID, "CUSTOMER"), OTHER_CUSTOMER_ID))
                .doesNotThrowAnyException();
    }

}
