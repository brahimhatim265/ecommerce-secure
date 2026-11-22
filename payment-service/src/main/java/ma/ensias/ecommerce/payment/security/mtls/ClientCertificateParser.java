package ma.ensias.ecommerce.payment.security.mtls;

import jakarta.servlet.http.HttpServletRequest;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the client certificate of a request and the common name (CN) of its subject.
 * <p>
 * The certificate comes from the standard Servlet attribute published by the container, never from a header, so a
 * client cannot forge it. By the time it is there, the TLS handshake has already validated it (chain, dates) against
 * the project CA.
 * <p>
 * The CN is read with the JDK LDAP API ({@link LdapName}/{@link Rdn}), not with a regular expression: escaped
 * characters are decoded and an attribute type such as "OU" can never be mistaken for a CN. Every doubtful case
 * gives NO name, which means NO identity and NO right.
 */
public final class ClientCertificateParser {

    /** Standard Servlet attribute holding the certificate chain presented by the client, leaf first. */
    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private static final String COMMON_NAME = "CN";

    /** The certificate of the client itself (first of the chain), or empty if no certificate was presented. */
    public Optional<X509Certificate> clientCertificate(HttpServletRequest request) {
        if (request.getAttribute(CERTIFICATE_ATTRIBUTE) instanceof X509Certificate[] chain && chain.length > 0) {
            return Optional.ofNullable(chain[0]);
        }
        return Optional.empty();
    }

    /**
     * The CN of the subject, or empty when it is absent, appears more than once (ambiguous), is not text, or when
     * the subject cannot be parsed. This method never throws on a malformed subject.
     */
    public Optional<String> commonName(X509Certificate certificate) {
        List<String> commonNames = new ArrayList<>();
        try {
            LdapName subject = new LdapName(certificate.getSubjectX500Principal().getName(X500Principal.RFC2253));
            for (Rdn rdn : subject.getRdns()) {
                // toAttributes() also covers multi-valued RDNs such as "CN=a+OU=b"; attribute types are case-insensitive
                Attribute attribute = rdn.toAttributes().get(COMMON_NAME);
                if (attribute == null) {
                    continue;
                }
                NamingEnumeration<?> values = attribute.getAll();
                while (values.hasMore()) {
                    if (!(values.next() instanceof String value)) {
                        return Optional.empty();
                    }
                    commonNames.add(value);
                }
            }
        } catch (NamingException e) {
            return Optional.empty();
        }
        return commonNames.size() == 1 ? Optional.of(commonNames.get(0)) : Optional.empty();
    }
}
