package ma.ensias.ecommerce.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("order-service API")
                .version("1.0.0")
                .description("Product catalog and orders. Creating an order calls user-service and payment-service. "
                        + "All endpoints are open (no access protection in this version)."));
    }
}
