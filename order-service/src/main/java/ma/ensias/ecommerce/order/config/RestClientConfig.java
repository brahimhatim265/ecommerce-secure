package ma.ensias.ecommerce.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The ONLY place where HTTP clients are created: one {@link RestClient} per remote service, with a configurable
 * base URL, a connect timeout (default 2 s) and a read timeout (default 5 s).
 * This is where mTLS and JWT relaying will be plugged in later.
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
    public RestClient userRestClient(RestClient.Builder builder, ClientHttpRequestFactory serviceRequestFactory,
                                     @Value("${services.user.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).requestFactory(serviceRequestFactory).build();
    }

    @Bean
    public RestClient paymentRestClient(RestClient.Builder builder, ClientHttpRequestFactory serviceRequestFactory,
                                        @Value("${services.payment.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).requestFactory(serviceRequestFactory).build();
    }
}
