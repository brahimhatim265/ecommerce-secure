package ma.ensias.ecommerce.payment.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The bean "access": the ownership (ABAC) and role rules that the authorization expressions use, for example
 * {@code @PreAuthorize("@access.isOwnerOrAdmin(authentication, #id)")}.
 * <p>
 * Contract, identical in the three services:
 * <ul>
 *   <li>the identity of the USER is the claim {@code sub} of the validated JWT, read as a UUID. Anything else
 *       (no authentication, anonymous, an authentication that is not a JWT, a {@code sub} that is not a UUID) gives
 *       NO identity;</li>
 *   <li>every method FAILS CLOSED: with no identity, a null argument or any doubt, the answer is {@code false};
 *       no method throws;</li>
 *   <li>{@link #isAdmin} reads the authority {@code ROLE_ADMIN} built from the claim {@code roles};</li>
 *   <li>none of these methods says anything about the calling SERVICE (certificate): that is the bean
 *       "callers". A rule that needs both writes both.</li>
 * </ul>
 * An administrator is NOT the owner of everything: {@link #isOwner} stays false for an administrator who does not own
 * the resource. {@link #isOwnerOrAdmin} is the rule that lets an administrator through.
 */
@Component("access")
public class AccessRules {

    public static final String ADMIN_AUTHORITY = JwtRolesConfig.ROLE_PREFIX + "ADMIN";

    /** The user identified by the token, or empty when the authentication carries no usable identity. */
    public Optional<UUID> subjectOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && jwtAuthentication.isAuthenticated()) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null) {
                try {
                    return Optional.of(UUID.fromString(subject));
                } catch (IllegalArgumentException notAUuid) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** True when the authentication holds the role ADMIN. */
    public boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(ADMIN_AUTHORITY::equals);
    }

    /** True when the user of the token is the owner of the resource (their {@code sub} equals {@code ownerId}). */
    public boolean isOwner(Authentication authentication, UUID ownerId) {
        return ownerId != null && subjectOf(authentication).map(ownerId::equals).orElse(false);
    }

    /** True for the owner of the resource, and for any administrator. */
    public boolean isOwnerOrAdmin(Authentication authentication, UUID ownerId) {
        return isAdmin(authentication) || isOwner(authentication, ownerId);
    }
}
