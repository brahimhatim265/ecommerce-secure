package ma.ensias.ecommerce.order.security;

import ma.ensias.ecommerce.order.controller.OrderController;
import ma.ensias.ecommerce.order.controller.ProductController;
import ma.ensias.ecommerce.order.service.OrderService;
import ma.ensias.ecommerce.order.service.ProductService;
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

import static ma.ensias.ecommerce.order.security.TestIdentities.ADMIN_ID;
import static ma.ensias.ecommerce.order.security.TestIdentities.CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ROLE rules of docs/matrice-acces.md, one test per endpoint, evaluated on the controller beans themselves
 * (through their security proxy) so that a rule is tested alone, without HTTP, validation or the database.
 * "Refused" means that the security rule stopped the call; "allowed" means that the call went past the rule (the
 * mocked service may then fail or return nothing, which is irrelevant here).
 * <p>
 * Who owns the resource (ABAC) is not tested here: see the ownership tests.
 */
@SpringJUnitConfig(classes = {MethodSecurityConfig.class, AccessRules.class, OrderController.class, ProductController.class})
class ControllerRoleRulesTest {

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private ProductService productService;

    @Autowired
    private OrderController orders;

    @Autowired
    private ProductController products;

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

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }


    // ------------------------------------------------------------ O1, O2 catalog: public

    @Test
    void o1_o2_theCatalogIsOpenToEveryone() {
        anonymous();
        assertThat(refused(() -> products.list(null))).isFalse();
        assertThat(refused(() -> products.get(1L))).isFalse();
        customer();
        assertThat(refused(() -> products.list(null))).isFalse();
        admin();
        assertThat(refused(() -> products.get(1L))).isFalse();
    }

    // ------------------------------------------------------------ O3, O4 catalog management: ADMIN

    @Test
    void o3_creatingAProductIsForAdministratorsOnly() {
        admin();
        assertThat(refused(() -> products.create(null))).isFalse();
        customer();
        assertThat(refused(() -> products.create(null))).isTrue();
        userWithoutRole();
        assertThat(refused(() -> products.create(null))).isTrue();
        anonymous();
        assertThat(refused(() -> products.create(null))).isTrue();
    }

    @Test
    void o4_updatingAProductIsForAdministratorsOnly() {
        admin();
        assertThat(refused(() -> products.update(1L, null))).isFalse();
        customer();
        assertThat(refused(() -> products.update(1L, null))).isTrue();
        anonymous();
        assertThat(refused(() -> products.update(1L, null))).isTrue();
    }

    // ------------------------------------------------------------ O5 place an order: CUSTOMER only

    @Test
    void o5_aCustomerCanPlaceAnOrder() {
        customer();
        assertThat(refused(() -> orders.create(null, authentication()))).isFalse();
    }

    @Test
    void o5_anAdministratorCannotPlaceAnOrder() {
        admin();
        assertThat(refused(() -> orders.create(null, authentication()))).isTrue();
    }

    @Test
    void o5_aUserWithoutRoleOrAnAnonymousCallerIsRefused() {
        userWithoutRole();
        assertThat(refused(() -> orders.create(null, authentication()))).isTrue();
        anonymous();
        assertThat(refused(() -> orders.create(null, authentication()))).isTrue();
    }

    // ------------------------------------------------------------ O6 read an order, O7 list orders

    @Test
    void o6_readingAnOrderNeedsARole() {
        customer();
        assertThat(refused(() -> orders.get(1L, authentication()))).isFalse();
        admin();
        assertThat(refused(() -> orders.get(1L, authentication()))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> orders.get(1L, authentication()))).isTrue();
        anonymous();
        assertThat(refused(() -> orders.get(1L, authentication()))).isTrue();
    }

    @Test
    void o7_listingOrdersNeedsARole() {
        customer();
        assertThat(refused(() -> orders.list(null, authentication()))).isFalse();
        admin();
        assertThat(refused(() -> orders.list(null, authentication()))).isFalse();
        userWithoutRole();
        assertThat(refused(() -> orders.list(null, authentication()))).isTrue();
        anonymous();
        assertThat(refused(() -> orders.list(null, authentication()))).isTrue();
    }
}
