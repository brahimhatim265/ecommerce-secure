package ma.ensias.ecommerce.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the validated simulation properties. */
@Configuration
@EnableConfigurationProperties(RandomSimulationProperties.class)
public class SimulationConfig {
}
