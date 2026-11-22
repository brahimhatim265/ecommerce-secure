package ma.ensias.ecommerce.user.security.mtls;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * The bean "callers": lets authorization expressions ask who the calling SERVICE is, for example
 * {@code @PreAuthorize("@callers.isService('SERVICE_ORDER')")}.
 * <p>
 * Contract of {@link #isService(String...)}:
 * <ul>
 *   <li>it is evaluated for the HTTP request being served by the current thread;</li>
 *   <li>the names are the identity names of the correspondence table: SERVICE_USER, SERVICE_ORDER, SERVICE_PAYMENT,
 *       SERVICE_AUTH; they are compared exactly (case-sensitive);</li>
 *   <li>it returns true only when the client presented a certificate whose CN identifies one of those services;</li>
 *   <li>it returns false when no certificate was presented, when the CN is unknown, when called outside an HTTP
 *       request, and when no name (or null) is given. It never throws;</li>
 *   <li>it says nothing about the user: the JWT is checked separately, so both can be required together.</li>
 * </ul>
 */
@Component("callers")
public class Callers {

    public boolean isService(String... names) {
        if (names == null || names.length == 0) {
            return false;
        }
        return currentCaller().service()
                .map(service -> Arrays.asList(names).contains(service.name()))
                .orElse(false);
    }

    private static CallerIdentity currentCaller() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(CallerIdentity.REQUEST_ATTRIBUTE) instanceof CallerIdentity caller) {
            return caller;
        }
        return CallerIdentity.noCertificate();
    }
}
