package ma.ensias.ecommerce.payment.security;

import ma.ensias.ecommerce.payment.controller.PaymentController;
import ma.ensias.ecommerce.payment.security.mtls.CallerIdentity;
import ma.ensias.ecommerce.payment.security.mtls.Callers;
import ma.ensias.ecommerce.payment.security.mtls.ServiceIdentity;
import ma.ensias.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static ma.ensias.ecommerce.payment.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.payment.security.TestIdentities.CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ROLE rules of docs/matrice-acces.md, one test per endpoint, evaluated on the controller beans themselves
 * (through their security proxy) so that a rule is tested alone, without HTTP, validation or the database.
 * "Refused" means that the security rule stopped the call; "allowed" means that the call went past the rule (the
 * mocked service may then fail or return nothing, which is irrelevant here).
 * <p>
 * Who owns the resource (ABAC) is not tested here: see the ownership tests.
 */
@SpringJUnitConfig(classes = {MethodSecurityConfig.class, AccessRules.class, Callers.class,
        PaymentController.class})
class ControllerRoleRulesTest {

    @MockitoBean
    private PaymentService paymentService;

    @Autowired
    private PaymentController payments;

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private interface Call {
        void run() throws Exception;
    }

    private static boolean refused(Call call) {
        try {
            call.run();
            return false;
        } catch (AccessDeniedException | AuthenticationException refusal) {
            return true;
        } catch (Exception passedTheRule) {
            return false;
        }
    }

    private static void authenticate(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static void anonymous() {
        authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    }

    private static void user(UUID subject, String... roles) {
        authenticate(TestIdentities.authentication(subject, roles));
    }

    private static void customer() {
        user(CUSTOMER_ID, "CUSTOMER");
    }

    private static void admin() {
        user(ADMIN_ID, "ADMIN");
    }

    private static void userWithoutRole() {
        user(CUSTOMER_ID);
    }

    /** The client certificate presented to the service, as the certificate filter would record it. */
    private static void certificate(CallerIdentity caller) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CallerIdentity.REQUEST_ATTRIBUTE, caller);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }


    private static final String KEY = "order-42";

    // ------------------------------------------------------------ P1 create a payment: SERVICE_ORDER, for a user

    @Test
    void p1_theOrderServiceActingForAUserIsAllowed() {
        customer();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> payments.create(KEY, null))).isFalse();
    }

    @Test
    void p1_theOrderServiceWithoutAUserIsRefused() {
        anonymous();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
    }

    @Test
    void p1_aUserWithoutTheCertificateIsRefused() {
        customer();
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
        admin();
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
    }

    @Test
    void p1_anotherServiceOrAnUnknownCertificateIsRefused() {
        customer();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_USER));
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_AUTH));
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
        certificate(CallerIdentity.unknownService("rogue-service"));
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
    }

    @Test
    void p1_noCallerAtAllIsRefused() {
        anonymous();
        assertThat(refused(() -> payments.create(KEY, null))).isTrue();
    }

    // ------------------------------------------------------------ P2, P3, P4 reading payments

    @Test
    void p2_readingAPaymentNeedsARole() {
        customer();
        assertThat(refused(() -> payments.get(1L))).isFalse();
        admin();
        assertThat(refused(() -> payments.get(1L))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> payments.get(1L))).isTrue();
        anonymous();
        assertThat(refused(() -> payments.get(1L))).isTrue();
    }

    @Test
    void p3_readingThePaymentOfAnOrderNeedsARole() {
        customer();
        assertThat(refused(() -> payments.getByOrder(1L))).isFalse();
        admin();
        assertThat(refused(() -> payments.getByOrder(1L))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> payments.getByOrder(1L))).isTrue();
        anonymous();
        assertThat(refused(() -> payments.getByOrder(1L))).isTrue();
    }

    @Test
    void p4_listingPaymentsNeedsARole() {
        customer();
        assertThat(refused(() -> payments.list(null))).isFalse();
        admin();
        assertThat(refused(() -> payments.list(null))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> payments.list(null))).isTrue();
        anonymous();
        assertThat(refused(() -> payments.list(null))).isTrue();
    }

    @Test
    void p2_p3_p4_aServiceCertificateAloneDoesNotGiveAccess() {
        anonymous();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> payments.get(1L))).isTrue();
        assertThat(refused(() -> payments.getByOrder(1L))).isTrue();
        assertThat(refused(() -> payments.list(null))).isTrue();
    }

    // ------------------------------------------------------------ P5 collect: ADMIN

    @Test
    void p5_collectingAPaymentIsForAdministratorsOnly() {
        admin();
        assertThat(refused(() -> payments.collect(1L))).isFalse();
        customer();
        assertThat(refused(() -> payments.collect(1L))).isTrue();
        anonymous();
        assertThat(refused(() -> payments.collect(1L))).isTrue();
    }

    @Test
    void p5_theOrderServiceCertificateDoesNotAllowToCollect() {
        customer();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> payments.collect(1L))).isTrue();
    }
}
