package ma.ensias.ecommerce.user.security.mtls;

import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;

/**
 * HTTPS clients for the TLS tests. They trust ONLY the project CA (certs/truststore.p12) and verify the host name, as
 * any real client of the project does; they differ by the client certificate they hold.
 */
public final class TestTlsClients {

    /** DEMONSTRATION password of the project keystores (public, documented in docs/). */
    private static final char[] PASSWORD = "ecommerce-demo-pki".toCharArray();

    private TestTlsClients() {
    }

    /** A client with no certificate at all (a browser, Postman, the web client...). */
    public static HttpClient withoutCertificate() {
        return build(null, false);
    }

    /**
     * A client that holds the certificate of certs/NAME.p12 and, like the JDK does by default, presents it only when
     * the server announces its issuer among the CAs it accepts.
     */
    public static HttpClient holding(String keystoreName) {
        return build(keystoreName, false);
    }

    /**
     * A client that presents the certificate of certs/NAME.p12 WHATEVER the server announces. A JDK client holding a
     * certificate from another CA would otherwise silently present none; this one makes the server judge it.
     */
    public static HttpClient insistingOnPresenting(String keystoreName) {
        return build(keystoreName, true);
    }

    private static HttpClient build(String keystoreName, boolean insist) {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load("certs/truststore.p12"));

            KeyManager[] keyManagers = null;
            if (keystoreName != null) {
                KeyStore keys = load("certs/" + keystoreName + ".p12");
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(keys, PASSWORD);
                X509ExtendedKeyManager manager = (X509ExtendedKeyManager) factory.getKeyManagers()[0];
                keyManagers = new KeyManager[] {insist ? new AlwaysPresenting(manager, keyAlias(keys)) : manager};
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trust.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build the test TLS client for " + keystoreName, e);
        }
    }

    private static KeyStore load(String resource) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            store.load(in, PASSWORD);
        }
        return store;
    }

    private static String keyAlias(KeyStore store) throws GeneralSecurityException {
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new IllegalStateException("No key entry in the test keystore");
    }

    /** Presents its key entry without looking at the issuers announced by the server. */
    private static final class AlwaysPresenting extends X509ExtendedKeyManager {

        private final X509ExtendedKeyManager delegate;
        private final String alias;

        AlwaysPresenting(X509ExtendedKeyManager delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {alias};
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            return alias;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String requested) {
            return delegate.getCertificateChain(requested);
        }

        @Override
        public PrivateKey getPrivateKey(String requested) {
            return delegate.getPrivateKey(requested);
        }
    }
}
