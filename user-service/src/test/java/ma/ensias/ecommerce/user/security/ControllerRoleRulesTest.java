package ma.ensias.ecommerce.user.security;

import ma.ensias.ecommerce.user.controller.AuthController;
import ma.ensias.ecommerce.user.controller.UserController;
import ma.ensias.ecommerce.user.security.mtls.CallerIdentity;
import ma.ensias.ecommerce.user.security.mtls.Callers;
import ma.ensias.ecommerce.user.security.mtls.ServiceIdentity;
import ma.ensias.ecommerce.user.service.AuthenticationService;
import ma.ensias.ecommerce.user.service.UserService;
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

import static ma.ensias.ecommerce.user.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.CUSTOMER_ID;
import static ma.ensias.ecommerce.user.security.TestIdentities.OTHER_CUSTOMER_ID;
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
        UserController.class, AuthController.class})
class ControllerRoleRulesTest {

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Autowired
    private UserController users;

    @Autowired
    private AuthController auth;

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


    // ------------------------------------------------------------ U1 sign-up: public

    @Test
    void u1_signUpIsOpenToEveryone() {
        anonymous();
        assertThat(refused(() -> users.create(null))).isFalse();
        customer();
        assertThat(refused(() -> users.create(null))).isFalse();
        admin();
        assertThat(refused(() -> users.create(null))).isFalse();
    }

    // ------------------------------------------------------------ U2 list and search: ADMIN

    @Test
    void u2_listAndSearchAreForAdministratorsOnly() {
        admin();
        assertThat(refused(() -> users.list(null))).isFalse();
        customer();
        assertThat(refused(() -> users.list(null))).isTrue();
        userWithoutRole();
        assertThat(refused(() -> users.list(null))).isTrue();
        anonymous();
        assertThat(refused(() -> users.list(null))).isTrue();
    }

    @Test
    void u2_aServiceCertificateDoesNotGiveTheAdministratorRole() {
        customer();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> users.list(null))).isTrue();
    }

    // ------------------------------------------------------------ U3 read a profile / U4 update a profile

    @Test
    void u3_readingAProfileNeedsARole() {
        customer();
        assertThat(refused(() -> users.get(CUSTOMER_ID))).isFalse();
        admin();
        assertThat(refused(() -> users.get(CUSTOMER_ID))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> users.get(CUSTOMER_ID))).isTrue();
        anonymous();
        assertThat(refused(() -> users.get(CUSTOMER_ID))).isTrue();
    }

    @Test
    void u4_updatingAProfileNeedsARole() {
        customer();
        assertThat(refused(() -> users.update(CUSTOMER_ID, null))).isFalse();
        admin();
        assertThat(refused(() -> users.update(CUSTOMER_ID, null))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> users.update(CUSTOMER_ID, null))).isTrue();
        anonymous();
        assertThat(refused(() -> users.update(CUSTOMER_ID, null))).isTrue();
    }

    // ------------------------------------------------------------ U5 account status: ADMIN

    @Test
    void u5_changingTheStatusOfAnAccountIsForAdministratorsOnly() {
        admin();
        assertThat(refused(() -> users.updateStatus(CUSTOMER_ID, null))).isFalse();
        customer();
        assertThat(refused(() -> users.updateStatus(CUSTOMER_ID, null))).isTrue();
        anonymous();
        assertThat(refused(() -> users.updateStatus(CUSTOMER_ID, null))).isTrue();
    }

    // ------------------------------------------------------------ U6 credentials check: SERVICE_AUTH only

    @Test
    void u6_theAuthorizationServerCertificateIsEnoughAndNeedsNoUser() {
        anonymous();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_AUTH));
        assertThat(refused(() -> auth.verify(null))).isFalse();
    }

    @Test
    void u6_noCertificateIsRefused() {
        anonymous();
        assertThat(refused(() -> auth.verify(null))).isTrue();
    }

    @Test
    void u6_anotherServiceIsRefused() {
        anonymous();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> auth.verify(null))).isTrue();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_PAYMENT));
        assertThat(refused(() -> auth.verify(null))).isTrue();
    }

    @Test
    void u6_anUnknownCertificateIsRefused() {
        anonymous();
        certificate(CallerIdentity.unknownService("rogue-service"));
        assertThat(refused(() -> auth.verify(null))).isTrue();
    }

    @Test
    void u6_aUserRoleDoesNotReplaceTheCertificate() {
        admin();
        assertThat(refused(() -> auth.verify(null))).isTrue();
        customer();
        assertThat(refused(() -> auth.verify(null))).isTrue();
        admin();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> auth.verify(null))).isTrue();
    }

    // ------------------------------------------------------------ A1 / A7 : a profile belongs to its owner

    @Test
    void a1_aCustomerReadsOnlyTheirOwnProfile() {
        customer();
        assertThat(refused(() -> users.get(CUSTOMER_ID))).isFalse();
        assertThat(refused(() -> users.get(OTHER_CUSTOMER_ID))).isTrue();
        admin();
        assertThat(refused(() -> users.get(OTHER_CUSTOMER_ID))).isFalse();
    }

    @Test
    void a1_aCustomerUpdatesOnlyTheirOwnProfile() {
        customer();
        assertThat(refused(() -> users.update(CUSTOMER_ID, null))).isFalse();
        assertThat(refused(() -> users.update(OTHER_CUSTOMER_ID, null))).isTrue();
        admin();
        assertThat(refused(() -> users.update(OTHER_CUSTOMER_ID, null))).isFalse();
    }

    @Test
    void a7_theOrderServiceReadsOnlyTheUserOfTheRelayedToken() {
        customer();
        certificate(CallerIdentity.service(ServiceIdentity.SERVICE_ORDER));
        assertThat(refused(() -> users.get(CUSTOMER_ID))).isFalse();
        assertThat(refused(() -> users.get(OTHER_CUSTOMER_ID))).isTrue();
    }
}
