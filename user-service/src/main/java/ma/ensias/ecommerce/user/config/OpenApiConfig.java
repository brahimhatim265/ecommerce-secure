package ma.ensias.ecommerce.user.config;

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
    public OpenAPI userServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("user-service API")
                        .version("1.0.0")
                        .description("Accounts, profiles and credential verification. Endpoints are protected by a JWT access token (Authorize button); public: POST /users, GET /actuator/health and, temporarily, POST /internal/auth/verify."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token (JWT) issued by the authorization server")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
