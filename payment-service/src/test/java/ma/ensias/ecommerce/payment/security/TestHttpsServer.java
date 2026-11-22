package ma.ensias.ecommerce.payment.security;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** A tiny HTTPS server for tests: it presents the certificate found in a keystore of the test classpath. */
final class TestHttpsServer implements AutoCloseable {

    /** DEMONSTRATION password of the project keystores (public, documented in docs/). */
    private static final char[] KEYSTORE_PASSWORD = "ecommerce-demo-pki".toCharArray();

    private final HttpsServer server;

    private TestHttpsServer(HttpsServer server) {
        this.server = server;
    }

    /** Starts a server on the given port (0 = any free port) with the certificate of the given keystore. */
    static TestHttpsServer start(String keystoreResource, int port) {
        try {
            HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext(keystoreResource)));
            server.start();
            return new TestHttpsServer(server);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot start the test HTTPS server", e);
        }
    }

    private static SSLContext sslContext(String keystoreResource) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new ClassPathResource(keystoreResource).getInputStream()) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Base URL as seen by a client; "localhost" is in the SAN of every project certificate. */
    String baseUrl() {
        return "https://localhost:" + port();
    }

    /** Answers GET requests on the path with a 200 and the given JSON body. */
    void handleJson(String path, String json) {
        server.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
