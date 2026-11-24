package ma.ensias.ecommerce.user.security.mtls;

import java.util.Optional;


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
