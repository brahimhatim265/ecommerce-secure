package ma.ensias.ecommerce.payment.security.mtls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract of the bean "callers" used in authorization expressions. */
class CallersTest {

    private final Callers callers = new Callers();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void duringARequestWhereTheCallerIs(CallerIdentity caller) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, caller);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void trueWhenTheCallerIsOneOfTheNamedServices() {
        duringARequestWhereTheCallerIs(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(callers.isService("SERVICE_ORDER")).isTrue();
        assertThat(callers.isService("SERVICE_AUTH", "SERVICE_ORDER")).isTrue();
        assertThat(callers.isService("SERVICE_ORDER", "SERVICE_ORDER")).isTrue();
    }

    @Test
    void falseWhenTheCallerIsAnotherService() {
        duringARequestWhereTheCallerIs(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(callers.isService("SERVICE_AUTH")).isFalse();
        assertThat(callers.isService("SERVICE_AUTH", "SERVICE_PAYMENT", "SERVICE_USER")).isFalse();
    }

    @Test
    void namesAreComparedExactlyAndCaseSensitively() {
        duringARequestWhereTheCallerIs(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(callers.isService("service_order")).isFalse();
        assertThat(callers.isService("order-service")).isFalse();
        assertThat(callers.isService(" SERVICE_ORDER")).isFalse();
        assertThat(callers.isService("SERVICE_ORDER ")).isFalse();
    }

    @Test
    void falseWithoutAnyName() {
        duringARequestWhereTheCallerIs(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));

        assertThat(callers.isService()).isFalse();
        assertThat(callers.isService((String[]) null)).isFalse();
        assertThat(callers.isService((String) null)).isFalse();
    }

    @Test
    void falseWhenNoCertificateWasPresented() {
        duringARequestWhereTheCallerIs(CallerIdentity.noCertificate());

        assertThat(callers.isService("SERVICE_ORDER", "SERVICE_AUTH", "SERVICE_USER", "SERVICE_PAYMENT")).isFalse();
    }

    @Test
    void falseWhenTheCertificateIsPresentedButUnknown() {
        duringARequestWhereTheCallerIs(CallerIdentity.unknownService("rogue-service"));

        assertThat(callers.isService("SERVICE_ORDER", "SERVICE_AUTH", "SERVICE_USER", "SERVICE_PAYMENT")).isFalse();
        assertThat(callers.isService("rogue-service", "UNKNOWN_SERVICE")).isFalse();
    }

    @Test
    void falseWhenTheFilterDidNotRun() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(callers.isService("SERVICE_ORDER")).isFalse();
    }

    @Test
    void falseWhenTheAttributeIsNotACallerIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, "SERVICE_ORDER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(callers.isService("SERVICE_ORDER")).isFalse();
    }

    @Test
    void falseOutsideAnyHttpRequest() {
        assertThat(callers.isService("SERVICE_ORDER")).isFalse();
    }
}
