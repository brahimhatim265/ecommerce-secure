package ma.ensias.ecommerce.order.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns a validated access token into a Spring Security authentication that carries the user's roles.
 * <p>
 * The authorization server puts the roles in the claim {@code roles}, WITHOUT prefix (for example
 * {@code ["CUSTOMER"]}, see docs/contrat-jwt.md). Spring's default converter reads the claim {@code scope} instead,
 * so without this bean no role would ever be recognised. Here:
 * <ul>
 *   <li>each value of {@code roles} becomes the authority {@code ROLE_<value>}, which is what
 *       {@code hasRole('ADMIN')} looks for;</li>
 *   <li>the {@code scope} claim is NOT turned into authorities: scopes say what the client application may ask,
 *       not what the user may do;</li>
 *   <li>the name of the authentication is the claim {@code sub} (the UUID of the user), the value that every
 *       ownership rule compares with {@code ownerId}.</li>
 * </ul>
 * A value that already starts with {@code ROLE_} is not treated as the role without it: {@code ["ROLE_ADMIN"]}
 * gives the authority {@code ROLE_ROLE_ADMIN}, never {@code ROLE_ADMIN}.
 * <p>
 * Service identities (certificate CN) are NOT authorities: they are read separately through the bean "callers",
 * so that a role of the token can never be mistaken for the identity of a service, or the other way round.
 */
@Configuration
public class JwtRolesConfig {

    public static final String ROLES_CLAIM = "roles";
    public static final String ROLE_PREFIX = "ROLE_";
    public static final String SUBJECT_CLAIM = "sub";

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix(ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(SUBJECT_CLAIM);
        return converter;
    }
}
