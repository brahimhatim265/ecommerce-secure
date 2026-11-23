package ma.ensias.ecommerce.user.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the default of the access matrix: an endpoint that nobody wrote a rule for must fail the build, not slip
 * into production open to every logged-in user.
 */
class EndpointAuthorizationCoverageTest {

    private static final String CONTROLLER_PACKAGE = "ma.ensias.ecommerce.user.controller";

    /** Number of endpoints in docs/matrice-acces.md for this service. Update it, and the matrix, with a new endpoint. */
    private static final int ENDPOINTS_IN_THE_MATRIX = 6;

    /** The public endpoints of the matrix (section "URL publiques"): the only ones allowed to say permitAll(). */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of("UserController#create");

    private static List<Method> endpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> endpoints = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isSynthetic() && !method.isBridge()
                        && AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    endpoints.add(method);
                }
            }
        }
        return endpoints;
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private static String rule(Method method) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        return annotation == null ? null : annotation.value().replace(" ", "");
    }

    @Test
    void everyEndpointOfTheMatrixIsFound() throws Exception {
        assertThat(endpoints()).hasSize(ENDPOINTS_IN_THE_MATRIX);
    }

    @Test
    void everyEndpointHasAnAuthorizationRule() throws Exception {
        Set<String> withoutRule = new TreeSet<>();
        for (Method endpoint : endpoints()) {
            String rule = rule(endpoint);
            if (rule == null || rule.isBlank()) {
                withoutRule.add(name(endpoint));
            }
        }
        assertThat(withoutRule).as("endpoints without @PreAuthorize").isEmpty();
    }

    @Test
    void onlyTheDocumentedPublicEndpointsArePermitAll() throws Exception {
        Set<String> permitAll = new TreeSet<>();
        for (Method endpoint : endpoints()) {
            if ("permitAll()".equals(rule(endpoint))) {
                permitAll.add(name(endpoint));
            }
        }
        assertThat(permitAll).as("endpoints open to everyone").containsExactlyInAnyOrderElementsOf(PUBLIC_ENDPOINTS);
    }
}
