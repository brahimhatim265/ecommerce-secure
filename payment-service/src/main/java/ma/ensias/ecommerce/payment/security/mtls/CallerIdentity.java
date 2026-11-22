package ma.ensias.ecommerce.payment.security.mtls;

import java.util.Optional;

/**
 * Who is calling, as far as the client certificate says. Exactly one of three situations, so that the authorization
 * rules can tell them apart (no certificate gives a 401, a certificate that is not allowed gives a 403):
 * <ul>
 *   <li>{@link Type#SERVICE}: the certificate carries the CN of a project service (see {@link ServiceIdentity});</li>
 *   <li>{@link Type#UNKNOWN_SERVICE}: a certificate was presented (so it is signed by the project CA) but its CN is
 *       not in the table, or it has no single usable CN: the caller has NO right;</li>
 *   <li>{@link Type#NO_CERTIFICATE}: no client certificate was presented (browser, Postman, client web...).</li>
 * </ul>
 * It is a plain description: it never grants anything by itself.
 */
public final class CallerIdentity {

    /** Name of the request attribute under which {@link ClientCertificateFilter} stores the identity. */
    public static final String REQUEST_ATTRIBUTE = CallerIdentity.class.getName();

    public enum Type { SERVICE, UNKNOWN_SERVICE, NO_CERTIFICATE }

    private static final CallerIdentity NO_CERTIFICATE = new CallerIdentity(Type.NO_CERTIFICATE, null, null);

    private final Type type;
    private final ServiceIdentity service;
    private final String commonName;

    private CallerIdentity(Type type, ServiceIdentity service, String commonName) {
        this.type = type;
        this.service = service;
        this.commonName = commonName;
    }

    public static CallerIdentity service(ServiceIdentity service) {
        return new CallerIdentity(Type.SERVICE, service, service.commonName());
    }

    /** A certificate was presented but gives no identity; {@code commonName} is null when it has no usable CN. */
    public static CallerIdentity unknownService(String commonName) {
        return new CallerIdentity(Type.UNKNOWN_SERVICE, null, commonName);
    }

    public static CallerIdentity noCertificate() {
        return NO_CERTIFICATE;
    }

    public Type type() {
        return type;
    }

    /** True when the client presented a certificate (recognised or not). */
    public boolean certificatePresented() {
        return type != Type.NO_CERTIFICATE;
    }

    /** The identified service, empty unless {@link #type()} is {@link Type#SERVICE}. */
    public Optional<ServiceIdentity> service() {
        return Optional.ofNullable(service);
    }

    /** The CN of the certificate, when there is one. It is only a name, and may be logged. */
    public Optional<String> commonName() {
        return Optional.ofNullable(commonName);
    }

    @Override
    public String toString() {
        return "CallerIdentity[" + type + (commonName != null ? ", cn=" + commonName : "") + "]";
    }
}
