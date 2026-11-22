package ma.ensias.ecommerce.user.security.mtls;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The service rule: who is granted, why the others are refused, and what is logged. */
class ServiceCallerAuthorizationManagerTest {

    private final ServiceCallerAuthorizationManager onlyAuth =
            ServiceCallerAuthorizationManager.onlyFrom(ServiceIdentity.SERVICE_AUTH);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger managerLogger = (Logger) LoggerFactory.getLogger(ServiceCallerAuthorizationManager.class);

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
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/verify");
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, caller);
        return request;
    }

    private static boolean granted(ServiceCallerAuthorizationManager rule, MockHttpServletRequest request) {
        AuthorizationResult result = rule.authorize(() -> null, new RequestAuthorizationContext(request));
        assertThat(result).as("the rule must always give a decision").isNotNull();
        return result.isGranted();
    }

    // ---------------------------------------------------------------- granted

    @Test
    void grantsTheAllowedService() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_AUTH));

        assertThat(granted(onlyAuth, request)).isTrue();
        assertThat(DenialReason.of(request)).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void grantsEachOfSeveralAllowedServicesAndNoOtherOne() {
        ServiceCallerAuthorizationManager rule =
                ServiceCallerAuthorizationManager.onlyFrom(ServiceIdentity.SERVICE_AUTH, ServiceIdentity.SERVICE_ORDER);

        assertThat(granted(rule, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_AUTH)))).isTrue();
        assertThat(granted(rule, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER)))).isTrue();
        assertThat(granted(rule, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_PAYMENT)))).isFalse();
        assertThat(granted(rule, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_USER)))).isFalse();
    }

    // ---------------------------------------------------------------- refused: no certificate (401)

    @Test
    void refusesARequestWithoutCertificate() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.noCertificate());

        assertThat(granted(onlyAuth, request)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_CERTIFICATE);
        assertThat(DenialReason.MISSING_CERTIFICATE.status().value()).isEqualTo(401);
    }

    @Test
    void failsClosedWhenTheFilterRecordedNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/verify");

        assertThat(granted(onlyAuth, request)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_CERTIFICATE);
    }

    @Test
    void failsClosedWhenTheRecordedValueIsNotACallerIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/verify");
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, "SERVICE_AUTH");

        assertThat(granted(onlyAuth, request)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.MISSING_CERTIFICATE);
    }

    // ---------------------------------------------------------------- refused: certificate not allowed (403)

    @Test
    void refusesAnotherProjectService() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(granted(onlyAuth, request)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.SERVICE_NOT_ALLOWED);
        assertThat(DenialReason.SERVICE_NOT_ALLOWED.status().value()).isEqualTo(403);
    }

    @Test
    void refusesAnUnknownService() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.unknownService("rogue-service"));

        assertThat(granted(onlyAuth, request)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.SERVICE_NOT_ALLOWED);
    }

    @Test
    void refusesACertificateWithoutUsableCommonName() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.unknownService(null));

        assertThat(granted(onlyAuth, request)).isFalse();
        assertThat(DenialReason.of(request)).contains(DenialReason.SERVICE_NOT_ALLOWED);
    }

    // ---------------------------------------------------------------- what is logged

    @Test
    void logsTheMissingCertificateAtWarnWithItsReasonCode() {
        granted(onlyAuth, requestFrom(CallerIdentity.noCertificate()));

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("missing_certificate", "POST", "/internal/auth/verify");
    }

    @Test
    void logsTheForbiddenServiceAtWarnWithItsReasonCodeAndItsCommonName() {
        granted(onlyAuth, requestFrom(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER)));

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("service_not_allowed", "order-service");
    }

    @Test
    void neverLogsATokenOrAPasswordOrTheBody() {
        MockHttpServletRequest request = requestFrom(CallerIdentity.unknownService("rogue-service"));
        request.addHeader("Authorization", "Bearer super-secret-token");
        request.setContent("{\"email\":\"someone@example.com\",\"password\":\"Demo@2026\"}".getBytes());

        granted(onlyAuth, request);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage())
                .doesNotContain("super-secret-token", "Demo@2026", "someone@example.com", "password");
    }
}
