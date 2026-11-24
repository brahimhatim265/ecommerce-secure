package ma.ensias.ecommerce.order.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class JwtDecoderConfig {

    static final String SSL_BUNDLE_NAME = "mtls";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            SslBundles sslBundles) {
        return lazyDecoder(issuerUri, sslBundles.getBundle(SSL_BUNDLE_NAME));
    }


    static JwtDecoder lazyDecoder(String issuerUri, SslBundle sslBundle) {
        RestOperations rest = httpsRestOperations(sslBundle);
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).restOperations(rest).build();
            decoder.setJwtValidator(TokenValidators.forIssuer(issuerUri));
            return decoder;
        });
    }

    private static RestOperations httpsRestOperations(SslBundle sslBundle) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)
                .withSslBundle(sslBundle);
        return new RestTemplate(ClientHttpRequestFactoryBuilder.jdk().build(settings));
    }
}
