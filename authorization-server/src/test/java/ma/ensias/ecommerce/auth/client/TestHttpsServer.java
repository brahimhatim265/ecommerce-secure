package ma.ensias.ecommerce.auth.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.springframework.core.io.ClassPathResource;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny HTTPS server for tests: it presents the certificate found in a keystore of the test classpath and, when asked
 * to, REQUIRES a client certificate signed by the CA of a truststore. Every request it serves is recorded, with the
 * common name (CN) of the client certificate that was presented.
 */
public final class TestHttpsServer implements AutoCloseable {

    /** What the server saw of one request. {@code clientCommonName} is null when no client certificate was presented. */
    public record RecordedRequest(String method, String path, Map<String, String> headers, String clientCommonName) {
    }

    /** DEMONSTRATION password of the project keystores (public, documented in docs/). */
    private static final char[] KEYSTORE_PASSWORD = "ecommerce-demo-pki".toCharArray();

    private final HttpsServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private TestHttpsServer(HttpsServer server) {
        this.server = server;
    }

    /** Starts a server that does not ask for a client certificate (port 0 = any free port). */
    public static TestHttpsServer start(String keystoreResource, int port) {
        return start(keystoreResource, null, port);
    }

    /**
     * Starts a server that REQUIRES a client certificate signed by the CA found in the truststore: a client without
     * one, or with one from another CA, fails during the TLS handshake.
     */
    public static TestHttpsServer startRequiringClientCertificate(String keystoreResource, String truststoreResource) {
        return start(keystoreResource, truststoreResource, 0);
    }

    private static TestHttpsServer start(String keystoreResource, String truststoreResource, int port) {
        try {
            HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
            boolean needClientAuth = truststoreResource != null;
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext(keystoreResource, truststoreResource)) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                    parameters.setNeedClientAuth(needClientAuth);
                    params.setSSLParameters(parameters);
                }
            });
            server.start();
            return new TestHttpsServer(server);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot start the test HTTPS server", e);
        }
    }

    private static SSLContext sslContext(String keystoreResource, String truststoreResource)
            throws IOException, GeneralSecurityException {
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(load(keystoreResource), KEYSTORE_PASSWORD);
        TrustManagerFactory trustManagers = null;
        if (truststoreResource != null) {
            trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(load(truststoreResource));
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers != null ? trustManagers.getTrustManagers() : null, null);
        return context;
    }

    private static KeyStore load(String resource) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            store.load(in, KEYSTORE_PASSWORD);
        }
        return store;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Base URL as seen by a client; "localhost" is in the SAN of every project certificate. */
    public String baseUrl() {
        return "https://localhost:" + port();
    }

    /** Answers every request on the path (whatever the method) with a 200 and the given JSON body, and records it. */
    public void handleJson(String path, String json) {
        server.createContext(path, exchange -> {
            requests.add(record(exchange));
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /** All the requests served so far, oldest first. */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** The most recent request served; fails if there is none. */
    public RecordedRequest lastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("The server did not receive any request");
        }
        return requests.get(requests.size() - 1);
    }

    private static RecordedRequest record(HttpExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        return new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                clientCommonName(exchange));
    }

    /** CN of the client certificate, read with the JDK LDAP API (not with a regular expression). */
    private static String clientCommonName(HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange https)) {
            return null;
        }
        try {
            Certificate[] chain = https.getSSLSession().getPeerCertificates();
            String subject = ((X509Certificate) chain[0]).getSubjectX500Principal().getName();
            for (Rdn rdn : new LdapName(subject).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
            return null;
        } catch (SSLPeerUnverifiedException | InvalidNameException e) {
            return null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
