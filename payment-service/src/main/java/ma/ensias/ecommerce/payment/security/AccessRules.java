package ma.ensias.ecommerce.payment.security;

import ma.ensias.ecommerce.payment.security.audit.AuditLog;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;


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
        Optional<UUID> subject = subjectOf(authentication);
        boolean owner = ownerId != null && subject.map(ownerId::equals).orElse(false);
        if (!owner && ownerId != null && subject.isPresent()) {
            AuditLog.hintNotOwner(); // only a note for the audit trail: a refusal that follows is "not_owner"
        }
        return owner;
    }

    /** True for the owner of the resource, and for any administrator. */
    public boolean isOwnerOrAdmin(Authentication authentication, UUID ownerId) {
        return isAdmin(authentication) || isOwner(authentication, ownerId);
    }

    public UUID ownerFilterFor(Authentication authentication, UUID requestedOwnerId) {
        if (isAdmin(authentication)) {
            return requestedOwnerId;
        }
        UUID subject = subjectOf(authentication)
                .orElseThrow(() -> new AccessDeniedException("No user identity"));
        if (requestedOwnerId != null && !requestedOwnerId.equals(subject)) {
            AuditLog.hintNotOwner();
            throw new AccessDeniedException("A user can only list their own resources");
        }
        return subject;
    }
}
