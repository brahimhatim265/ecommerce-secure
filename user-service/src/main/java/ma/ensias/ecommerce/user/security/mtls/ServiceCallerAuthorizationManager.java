package ma.ensias.ecommerce.user.security.mtls;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The authorization rule of an endpoint reserved to some project services: the request is granted only when the caller
 * identified from its client certificate (see {@link ClientCertificateFilter}) is one of the allowed services.
 * <p>
 * It is applied by Spring Security's authorization layer, and it FAILS CLOSED: if the filter did not run, or recorded
 * nothing usable, the caller counts as "no certificate" and the request is refused. It asks for no JWT: the
 * authentication of the user, when an endpoint needs it, is a separate rule.
 * <p>
 * A refusal records its {@link DenialReason} on the request and is logged at WARN with the reason code, the method,
 * the path and the caller (the CN may be logged; nothing else about the certificate, no header, no body).
 */
public final class ServiceCallerAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(ServiceCallerAuthorizationManager.class);

    private final Set<ServiceIdentity> allowedServices;

    private ServiceCallerAuthorizationManager(Set<ServiceIdentity> allowedServices) {
        this.allowedServices = allowedServices;
    }

    /** A rule that grants the request only to the given services. */
    public static ServiceCallerAuthorizationManager onlyFrom(ServiceIdentity first, ServiceIdentity... others) {
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
        return new AuthorizationDecision(true);
    }

    private static AuthorizationDecision refuse(HttpServletRequest request, DenialReason reason, CallerIdentity caller) {
        request.setAttribute(DenialReason.REQUEST_ATTRIBUTE, reason);
        log.warn("Access refused: reason={}, method={}, path={}, caller={}", reason.code(), request.getMethod(),
                request.getRequestURI(), caller);
        return new AuthorizationDecision(false);
    }
}
