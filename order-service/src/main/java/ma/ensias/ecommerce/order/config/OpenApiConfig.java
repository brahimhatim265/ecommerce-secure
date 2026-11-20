package ma.ensias.ecommerce.order.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI orderServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("order-service API")
                        .version("1.0.0")
                        .description("Product catalog and orders. Creating an order calls user-service and payment-service with the caller's token. Catalog reads (GET /products) are public; everything else requires a JWT access token (Authorize button)."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token (JWT) issued by the authorization server")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
