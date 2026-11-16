package ma.ensias.ecommerce.user.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("user-service API")
                .version("1.0.0")
                .description("Accounts, profiles and credential verification. All endpoints are open "
                        + "(no access protection in this version)."));
    }
}
