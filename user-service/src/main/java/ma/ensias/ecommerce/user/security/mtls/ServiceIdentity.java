package ma.ensias.ecommerce.user.security.mtls;

import java.util.Optional;

/**
 * The identity of a project service, derived from the common name (CN) of its client certificate.
 * <p>
 * This enum IS the correspondence table: exactly four entries, matched EXACTLY (case-sensitive, no trimming, no
 * pattern). A CN that is not in the table has no identity and therefore no right.
 * <p>
 * The CN is only a name. What makes it trustworthy is that the TLS handshake has already validated the certificate
 * chain against the project CA (truststore of the SSL bundle) before the application ever sees the request.
 */
public enum ServiceIdentity {

    SERVICE_USER("user-service"),
    SERVICE_ORDER("order-service"),
    SERVICE_PAYMENT("payment-service"),
    SERVICE_AUTH("authorization-server");

    private final String commonName;

    ServiceIdentity(String commonName) {
        this.commonName = commonName;
    }

    /** The CN that the certificate of this service carries. */
    public String commonName() {
        return commonName;
    }

    /** The service whose certificate carries exactly this CN, or empty if none (including null). */
    public static Optional<ServiceIdentity> fromCommonName(String commonName) {
        for (ServiceIdentity identity : values()) {
            if (identity.commonName.equals(commonName)) {
                return Optional.of(identity);
            }
        }
        return Optional.empty();
    }
}
