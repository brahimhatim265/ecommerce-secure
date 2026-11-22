package ma.ensias.ecommerce.payment.security;

import ma.ensias.ecommerce.payment.security.mtls.ClientCertificateFilter;
import ma.ensias.ecommerce.payment.security.mtls.ClientCertificateParser;
import ma.ensias.ecommerce.payment.security.mtls.ServiceCallerAuthorizationManager;
import ma.ensias.ecommerce.payment.security.mtls.ServiceIdentity;
import ma.ensias.ecommerce.payment.security.mtls.ServiceRuleDeniedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiAuthenticationEntryPoint authenticationEntryPoint,
                                                   ApiAccessDeniedHandler accessDeniedHandler,
                                                   ApiErrorWriter errorWriter,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        // 401 or 403 according to why the service rule refused a request; everything else is left to the handlers above
        ServiceRuleDeniedHandler serviceRuleDeniedHandler =
                new ServiceRuleDeniedHandler(authenticationEntryPoint, accessDeniedHandler);
        http
                // CSRF protects cookie-based sessions. This API has no session and no authentication cookie:
                // the JWT travels in the Authorization header, which a browser never adds on its own.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Reserved to order-service (client certificate, mTLS) acting on behalf of a user: the JWT that
                        // order-service relays must be present too. Both are checked by the authorization layer.
                        .requestMatchers(HttpMethod.POST, "/payments")
                        .access(ServiceCallerAuthorizationManager.onlyFromOnBehalfOfAUser(ServiceIdentity.SERVICE_ORDER))
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(serviceRuleDeniedHandler)
                        .accessDeniedHandler(serviceRuleDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // who is calling, according to the client certificate: read by the service rule above
                .addFilterBefore(new ClientCertificateFilter(new ClientCertificateParser()),
                        BearerTokenAuthenticationFilter.class)
                // 503 (not a fake 401) when the authorization server cannot be reached
                .addFilterBefore(new AuthorizationServerUnavailableFilter(errorWriter),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
