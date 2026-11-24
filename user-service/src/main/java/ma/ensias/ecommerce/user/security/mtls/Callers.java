package ma.ensias.ecommerce.user.security.mtls;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;


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
