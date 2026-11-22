package ma.ensias.ecommerce.payment.security.mtls;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;

/** The client certificates of the test keystores (certs/NAME.p12), as the servlet container would publish them. */
public final class TestCertificates {

    /** DEMONSTRATION password of the project keystores (public, documented in docs/). */
    private static final char[] KEYSTORE_PASSWORD = "ecommerce-demo-pki".toCharArray();

    private TestCertificates() {
    }

    /** The chain of the keystore's key entry, the client's own certificate first (like the request attribute). */
    public static X509Certificate[] chain(String keystoreName) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = new ClassPathResource("certs/" + keystoreName + ".p12").getInputStream()) {
                store.load(in, KEYSTORE_PASSWORD);
            }
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    Certificate[] chain = store.getCertificateChain(alias);
                    return Arrays.copyOf(chain, chain.length, X509Certificate[].class);
                }
            }
            throw new IllegalStateException("No key entry in the test keystore " + keystoreName);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load the test certificate " + keystoreName, e);
        }
    }

    /** The client's own certificate (first of the chain). */
    public static X509Certificate certificate(String keystoreName) {
        return chain(keystoreName)[0];
    }
}
