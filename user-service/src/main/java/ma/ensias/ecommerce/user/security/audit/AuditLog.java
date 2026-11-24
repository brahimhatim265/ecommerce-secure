package ma.ensias.ecommerce.user.security.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ma.ensias.ecommerce.user.security.mtls.CallerIdentity;

import java.util.UUID;

/**
 * The audit trail of access decisions: ONE line per request, written by the logger named {@code AUDIT}, in the format
 * of docs/matrice-acces.md (section 10):
 * <pre>
 * AUDIT decision=DENY reason=not_owner method=GET path=/orders/42 user=00000000-0000-4000-8000-000000000002 caller=-
 * </pre>
 * <ul>
 *   <li>{@code decision}: ALLOW or DENY; {@code reason}: the code of the refusal, {@code -} when allowed;</li>
 *   <li>{@code path}: the path only, WITHOUT the query string (an email can be in it);</li>
 *   <li>{@code user}: the claim {@code sub} of the validated token when it is a UUID, otherwise {@code -}: never an
 *       email or a name;</li>
 *   <li>{@code caller}: the CN of the client certificate when there is one, otherwise {@code -}.</li>
 * </ul>
 * Nothing else is ever written: no token, no {@code Authorization} header, no password, no body. Every value is
 * reduced to printable characters without spaces, so that a crafted URL cannot forge a second line.
 * <p>
 * The first decision recorded for a request wins: the later calls are ignored, so that the same refusal, seen by the
 * security rule and then by the error handler, is written once. A request that nobody refused is written as ALLOW
 * by {@link AuditFilter} when it has been served.
 */
public final class AuditLog {

    public static final String MISSING_TOKEN = "missing_token";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String MISSING_CERTIFICATE = "missing_certificate";
    public static final String SERVICE_NOT_ALLOWED = "service_not_allowed";
    public static final String INSUFFICIENT_ROLE = "insufficient_role";
    public static final String NOT_OWNER = "not_owner";

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final String NONE = "-";
    private static final int MAX_LENGTH = 200;
    private static final String RECORDED = AuditLog.class.getName() + ".recorded";
    private static final String REASON_HINT = AuditLog.class.getName() + ".reasonHint";

    private AuditLog() {
    }

    /** The request was served: the caller was allowed. */
    public static void allow(HttpServletRequest request) {
        record(request, true, NONE);
    }

    /** The request was refused, for this reason (one of the constants above). */
    public static void deny(HttpServletRequest request, String reason) {
        record(request, false, reason);
    }

    /**
     * Called by the ownership rules when an identified user is NOT the owner: a refusal that follows is then
     * recorded as {@link #NOT_OWNER} instead of {@link #INSUFFICIENT_ROLE}. It only leaves a note on the current
     * request; it grants and refuses nothing.
     */
    public static void hintNotOwner() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(REASON_HINT, NOT_OWNER);
        }
    }

    /** The reason of a refusal raised by an authorization rule: the hint left by an ownership rule, or the role. */
    public static String denialReasonOf(HttpServletRequest request) {
        return request.getAttribute(REASON_HINT) instanceof String hint ? hint : INSUFFICIENT_ROLE;
    }

    private static void record(HttpServletRequest request, boolean allowed, String reason) {
        if (request.getAttribute(RECORDED) != null) {
            return;
        }
        request.setAttribute(RECORDED, Boolean.TRUE);
        String line = "AUDIT decision={} reason={} method={} path={} user={} caller={}";
        if (allowed) {
            AUDIT.info(line, "ALLOW", NONE, clean(request.getMethod()), clean(request.getRequestURI()), user(),
                    caller(request));
        } else {
            AUDIT.warn(line, "DENY", clean(reason), clean(request.getMethod()), clean(request.getRequestURI()),
                    user(), caller(request));
        }
    }

    private static String user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()
                && jwt.getToken().getSubject() != null) {
            try {
                return UUID.fromString(jwt.getToken().getSubject()).toString();
            } catch (IllegalArgumentException notAUuid) {
                return NONE;
            }
        }
        return NONE;
    }

    private static String caller(HttpServletRequest request) {
        if (request.getAttribute(CallerIdentity.REQUEST_ATTRIBUTE) instanceof CallerIdentity caller) {
            return caller.commonName().map(AuditLog::clean).orElse(NONE);
        }
        return NONE;
    }

    /** Printable ASCII without spaces, at most {@value #MAX_LENGTH} characters: one token, one line. */
    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return NONE;
        }
        String printable = value.replaceAll("[^\\x21-\\x7E]", "_");
        return printable.length() > MAX_LENGTH ? printable.substring(0, MAX_LENGTH) : printable;
    }
}
