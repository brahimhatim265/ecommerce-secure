package ma.ensias.ecommerce.payment.security.mtls;

import ma.ensias.ecommerce.payment.security.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The authorization rule of an endpoint reserved to some project services ACTING ON BEHALF OF A USER: the request is
 * granted only when
 * <ol>
 *   <li>the client presented a certificate, and</li>
 *   <li>the caller identified from it (see {@link ClientCertificateFilter}) is one of the allowed services, and</li>
 *   <li>an authenticated user is present: the JWT relayed by the calling service, already validated by Spring
 *       Security before this rule runs (a missing, invalid or expired token never gets here: it is a 401 earlier).</li>
 * </ol>
 * The two identities stay separate: the service comes from the certificate, the user from the JWT. The checks are made
 * in this order, so a service that is not allowed gets a 403 whether or not it sent a token.
 * <p>
 * It is applied by Spring Security's authorization layer, and it FAILS CLOSED: if the filter did not run, or recorded
 * nothing usable, the caller counts as "no certificate" and the request is refused.
 * <p>
 * A refusal records its {@link DenialReason} on the request and is written to the audit trail (see
 * {@link AuditLog}): the reason code, the method, the path and the CN of the caller, nothing else.
 */
public final class ServiceCallerAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {


    private final Set<ServiceIdentity> allowedServices;

    private ServiceCallerAuthorizationManager(Set<ServiceIdentity> allowedServices) {
        this.allowedServices = allowedServices;
    }

    /** A rule that grants the request only to the given services, and only when an authenticated user is present. */
    public static ServiceCallerAuthorizationManager onlyFromOnBehalfOfAUser(ServiceIdentity first,
                                                                            ServiceIdentity... others) {
        return new ServiceCallerAuthorizationManager(EnumSet.of(first, others));
    }

    // check() is the only abstract method of the interface in Spring Security 6.5, although it is deprecated there
    @Override
    @SuppressWarnings("deprecation")
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        CallerIdentity caller = request.getAttribute(CallerIdentity.REQUEST_ATTRIBUTE) instanceof CallerIdentity recorded
                ? recorded : CallerIdentity.noCertificate();

        if (!caller.certificatePresented()) {
            return refuse(request, DenialReason.MISSING_CERTIFICATE, caller);
        }
        if (caller.service().filter(allowedServices::contains).isEmpty()) {
            return refuse(request, DenialReason.SERVICE_NOT_ALLOWED, caller);
        }
        // consulted only now: a refused service never reaches this point
        if (!userAuthenticated(authentication)) {
            return refuse(request, DenialReason.MISSING_TOKEN, caller);
        }
        return new AuthorizationDecision(true);
    }

    private static boolean userAuthenticated(Supplier<Authentication> authentication) {
        Authentication current = authentication.get();
        return current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken);
    }

    private static AuthorizationDecision refuse(HttpServletRequest request, DenialReason reason, CallerIdentity caller) {
        request.setAttribute(DenialReason.REQUEST_ATTRIBUTE, reason);
        AuditLog.deny(request, reason.code());
        return new AuthorizationDecision(false);
    }
}
