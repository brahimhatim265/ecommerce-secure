package ma.ensias.ecommerce.user.security.mtls;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

import java.util.Optional;


public enum DenialReason {

    /** No client certificate was presented: 401. */
    MISSING_CERTIFICATE("missing_certificate", HttpStatus.UNAUTHORIZED),

    /** A certificate was presented (so it is signed by the project CA) but its owner may not call this endpoint: 403. */
    SERVICE_NOT_ALLOWED("service_not_allowed", HttpStatus.FORBIDDEN);

    /** Request attribute holding the reason of the refusal. */
    static final String REQUEST_ATTRIBUTE = DenialReason.class.getName();

    private final String code;
    private final HttpStatus status;

    DenialReason(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    /** The reason recorded on this request by the rule, if the rule refused it. */
    public static Optional<DenialReason> of(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof DenialReason reason
                ? Optional.of(reason) : Optional.empty();
    }
}
