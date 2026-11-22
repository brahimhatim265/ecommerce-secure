package ma.ensias.ecommerce.payment.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Switches on the authorization annotations ({@code @PreAuthorize}, {@code @PostAuthorize}) on the controllers.
 * <p>
 * The URL rules of {@link SecurityConfig} only decide who may reach the service at all (public URLs, authenticated
 * users, endpoints reserved to a service). The detailed rule of every endpoint (role, owner of the resource,
 * calling service) is written next to the endpoint itself and follows docs/matrice-acces.md.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
