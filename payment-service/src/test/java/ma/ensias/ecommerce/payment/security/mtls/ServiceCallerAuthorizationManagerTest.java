package ma.ensias.ecommerce.payment.security.mtls;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The service rule of POST /payments: who is granted, why the others are refused, and what is logged. */
class ServiceCallerAuthorizationManagerTest {

    private final ServiceCallerAuthorizationManager onlyOrder =
            ServiceCallerAuthorizationManager.onlyFromOnBehalfOfAUser(ServiceIdentity.SERVICE_ORDER);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger managerLogger = (Logger) LoggerFactory.getLogger(ServiceCallerAuthorizationManager.class);

    private static final Supplier<Authentication> A_USER =
            () -> new TestingAuthenticationToken("someone", "n/a", "ROLE_CUSTOMER");
    private static final Supplier<Authentication> ANONYMOUS =
            () -> new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    /** Proves that a refusal made on the certificate alone never looks at the user. */
    private static final Supplier<Authentication> MUST_NOT_BE_CONSULTED =
            () -> {
                throw new AssertionError("the authentication must not be consulted when the certificate is refused");
            };

    @BeforeEach
    void captureLogs() {
        logs.start();
        managerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        managerLogger.detachAppender(logs);
        logs.stop();
    }

    private static MockHttpServletRequest requestFrom(CallerIdentity caller) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, caller);
        return request;
    }

    private static boolean granted(ServiceCallerAuthorizationManager rule, MockHttpServletRequest request,
                                   Supplier<Authentication> authentication) {
        AuthorizationResult result = rule.authorize(authentication, new RequestAuthorizationContext(request));
        assertThat(result).as("the rule must always give a decision").isNotNull();
        return result.isGranted();
    }

    // ---------------------------------------------------------------- granted

    @Test
    void grantsOrderServiceActingOnBehalfOfAUser() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(granted(onlyOrder, request, A_USER)).isTrue();
        assertThat(DenialReason.of(request)).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    // ---------------------------------------------------------------- 401: no certificate

    @Test
    void refusesARequestWithoutCertificateEvenWithAUser() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.noCertificate());

        assertThat(granted(onlyOrder, request, MUST_NOT_BE_CONSULTED)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_CERTIFICATE);
        assertThat(DenialReason.MISSING_CERTIFICATE.status().value()).isEqualTo(401);
    }

    @Test
    void failsClosedWhenTheFilterRecordedNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");

        assertThat(granted(onlyOrder, request, A_USER)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_CERTIFICATE);
    }

    // ---------------------------------------------------------------- 403: certificate present, service not allowed

    @Test
    void refusesAnotherProjectServiceWithoutLookingAtTheUser() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_AUTH));

        assertThat(granted(onlyOrder, request, MUST_NOT_BE_CONSULTED)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.SERVICE_NOT_ALLOWED);
        assertThat(DenialReason.SERVICE_NOT_ALLOWED.status().value()).isEqualTo(403);
    }

    @Test
    void refusesAnUnknownServiceWithoutLookingAtTheUser() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.unknownService("rogue-service"));

        assertThat(granted(onlyOrder, request, MUST_NOT_BE_CONSULTED)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.SERVICE_NOT_ALLOWED);
    }

    @Test
    void refusesAnotherServiceEvenWithAValidUser() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_USER));

        assertThat(granted(onlyOrder, request, A_USER)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.SERVICE_NOT_ALLOWED);
    }

    // ---------------------------------------------------------------- 401: allowed service, but no user

    @Test
    void refusesOrderServiceWhenNoUserIsAuthenticated() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(granted(onlyOrder, request, ANONYMOUS)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_TOKEN);
        assertThat(DenialReason.MISSING_TOKEN.status().value()).isEqualTo(401);
    }

    @Test
    void refusesOrderServiceWhenThereIsNoAuthenticationAtAll() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(granted(onlyOrder, request, () -> null)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_TOKEN);
    }

    @Test
    void refusesOrderServiceWhenTheAuthenticationIsNotAuthenticated() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        TestingAuthenticationToken notAuthenticated = new TestingAuthenticationToken("someone", "n/a", "ROLE_CUSTOMER");
        notAuthenticated.setAuthenticated(false);

        assertThat(granted(onlyOrder, request, () -> notAuthenticated)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_TOKEN);
    }

    // ---------------------------------------------------------------- what is logged

    @Test
    void logsEachReasonAtWarnWithItsCode() {
        granted(onlyOrder, requestFrom(CallerIdentity.noCertificate()), MUST_NOT_BE_CONSULTED);
        granted(onlyOrder, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_AUTH)), MUST_NOT_BE_CONSULTED);
        granted(onlyOrder, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER)), ANONYMOUS);

        assertThat(logs.list).hasSize(3);
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(logs.list.get(0).getFormattedMessage()).contains("missing_certificate", "POST", "/payments");
        assertThat(logs.list.get(1).getFormattedMessage()).contains("service_not_allowed", "authorization-server");
        assertThat(logs.list.get(2).getFormattedMessage()).contains("missing_token", "order-service");
    }

    @Test
    void neverLogsATokenOrACardTokenOrTheBody() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        request.addHeader("Authorization", "Bearer super-secret-token");
        request.addHeader("Idempotency-Key", "order-42");
        request.setContent("{\"cardToken\":\"tok_visa_approved\",\"amount\":6780.00}".getBytes());

        granted(onlyOrder, request, ANONYMOUS);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage())
                .doesNotContain("super-secret-token", "tok_visa_approved", "6780", "cardToken", "order-42");
    }
}
