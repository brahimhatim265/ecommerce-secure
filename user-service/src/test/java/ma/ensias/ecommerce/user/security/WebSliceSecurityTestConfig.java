package ma.ensias.ecommerce.user.security;

import ma.ensias.ecommerce.user.security.mtls.Callers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Brings the REAL security configuration into {@code @WebMvcTest} slices (slices do not scan configuration
 * classes or components), with the test decoder instead of the production one.
 */
@TestConfiguration
@Import({SecurityConfig.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        ApiErrorWriter.class, TestJwtDecoderConfig.class,
        JwtRolesConfig.class, MethodSecurityConfig.class, AccessRules.class,
        Callers.class})
public class WebSliceSecurityTestConfig {
}
