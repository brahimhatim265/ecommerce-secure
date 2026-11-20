package ma.ensias.ecommerce.order.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Replaces the production decoder (which would contact the authorization server) in tests. */
@TestConfiguration
public class TestJwtDecoderConfig {

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return TestJwtSupport.decoder();
    }
}
