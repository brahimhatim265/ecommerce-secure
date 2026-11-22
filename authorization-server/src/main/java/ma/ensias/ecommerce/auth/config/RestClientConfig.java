package ma.ensias.ecommerce.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The ONLY place where HTTP clients are created.
 * <p>
 * mTLS: the client is built from the SSL bundle "mtls" (application.yml). It therefore PRESENTS the certificate of
 * authorization-server when user-service asks for it, and trusts ONLY the project CA: a server with any other
 * certificate is refused during the TLS handshake, before the request (and the password it carries) is sent.
 */
@Configuration
public class RestClientConfig {

    /** The SSL bundle declared in application.yml: keystore of this service and truststore holding the project CA. */
    private static final String SSL_BUNDLE_NAME = "mtls";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient userServiceRestClient(RestClient.Builder builder, SslBundles sslBundles,
                                            @Value("${services.user.base-url}") String baseUrl) {
        // The JDK HTTP client applies the whole SSL bundle (certificate, CA and TLS protocols); HTTP/1.1 is kept as before.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)
                .withSslBundle(sslBundles.getBundle(SSL_BUNDLE_NAME));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(httpClient -> httpClient.version(HttpClient.Version.HTTP_1_1))
                .build(settings);

        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
