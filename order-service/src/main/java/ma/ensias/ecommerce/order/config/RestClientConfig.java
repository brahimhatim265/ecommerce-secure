package ma.ensias.ecommerce.order.config;

import ma.ensias.ecommerce.order.security.BearerTokenRelayInterceptor;
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
 * The ONLY place where HTTP clients are created: one {@link RestClient} per remote service, with a configurable
 * base URL, a connect timeout (default 2 s), a read timeout (default 5 s) and the relay of the caller's JWT
 * ({@link BearerTokenRelayInterceptor}).
 * <p>
 * mTLS: every client is built from the SSL bundle "mtls" (application.yml). It therefore PRESENTS the certificate of
 * order-service when the remote service asks for it, and trusts ONLY the project CA: a server with any other
 * certificate is refused during the TLS handshake, before a single byte of the request (JWT included) is sent.
 */
@Configuration
public class RestClientConfig {

    /** The SSL bundle declared in application.yml: keystore of this service and truststore holding the project CA. */
    private static final String SSL_BUNDLE_NAME = "mtls";

    /**
     * The JDK HTTP client is used on purpose: it applies the whole SSL bundle (certificate, CA and TLS protocols),
     * whereas the "simple" request factory refuses a bundle that restricts the protocols. HTTP/1.1 is forced so that
     * the behaviour stays the same as before (and the same as in authorization-server).
     */
    @Bean
    public ClientHttpRequestFactory serviceRequestFactory(
            SslBundles sslBundles,
            @Value("${services.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${services.http.read-timeout:5s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(connectTimeout, readTimeout)
                .withSslBundle(sslBundles.getBundle(SSL_BUNDLE_NAME));
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(httpClient -> httpClient.version(HttpClient.Version.HTTP_1_1))
                .build(settings);
    }

    @Bean
    public BearerTokenRelayInterceptor bearerTokenRelayInterceptor() {
        return new BearerTokenRelayInterceptor();
    }

    @Bean
    public RestClient userRestClient(RestClient.Builder builder, ClientHttpRequestFactory serviceRequestFactory,
                                     BearerTokenRelayInterceptor tokenRelay,
                                     @Value("${services.user.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).requestFactory(serviceRequestFactory)
                .requestInterceptor(tokenRelay).build();
    }

    @Bean
    public RestClient paymentRestClient(RestClient.Builder builder, ClientHttpRequestFactory serviceRequestFactory,
                                        BearerTokenRelayInterceptor tokenRelay,
                                        @Value("${services.payment.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).requestFactory(serviceRequestFactory)
                .requestInterceptor(tokenRelay).build();
    }
}
