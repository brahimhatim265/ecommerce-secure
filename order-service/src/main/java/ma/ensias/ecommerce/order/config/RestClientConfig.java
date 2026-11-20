package ma.ensias.ecommerce.order.config;

import ma.ensias.ecommerce.order.security.BearerTokenRelayInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The ONLY place where HTTP clients are created: one {@link RestClient} per remote service, with a configurable
 * base URL, a connect timeout (default 2 s), a read timeout (default 5 s) and the relay of the caller's JWT
 * ({@link BearerTokenRelayInterceptor}). This is where mTLS will be plugged in later.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public ClientHttpRequestFactory serviceRequestFactory(
            @Value("${services.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${services.http.read-timeout:5s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
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
