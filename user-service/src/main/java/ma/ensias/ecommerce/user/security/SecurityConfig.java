package ma.ensias.ecommerce.user.security;

import ma.ensias.ecommerce.user.security.mtls.ClientCertificateFilter;
import ma.ensias.ecommerce.user.security.mtls.ClientCertificateParser;
import ma.ensias.ecommerce.user.security.mtls.ServiceCallerAuthorizationManager;
import ma.ensias.ecommerce.user.security.mtls.ServiceIdentity;
import ma.ensias.ecommerce.user.security.mtls.ServiceRuleDeniedHandler;
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
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Reserved to the authorization server, identified by its client certificate (mTLS).
                        // No JWT is asked: the authorization server has none yet when it verifies a login.
                        // The rule is applied by the authorization layer and refuses the request when in doubt.
                        .requestMatchers(HttpMethod.POST, "/internal/auth/verify")
                        .access(ServiceCallerAuthorizationManager.onlyFrom(ServiceIdentity.SERVICE_AUTH))
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
