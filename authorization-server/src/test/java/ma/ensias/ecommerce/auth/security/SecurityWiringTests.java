package ma.ensias.ecommerce.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SecurityWiringTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void noProvisionalUserIsConfigured() {
        assertThat(environment.getProperty("spring.security.user.name")).isNull();
        assertThat(environment.getProperty("spring.security.user.password")).isNull();
    }

    @Test
    void noInMemoryUserStoreExists() {
        // Boot only creates its generated-password user when no provider / user store is defined.
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void userServiceProviderIsTheOnlyAuthenticationProvider() {
        assertThat(context.getBeansOfType(AuthenticationProvider.class).values())
                .hasSize(1)
                .first()
                .isInstanceOf(UserServiceAuthenticationProvider.class);
    }
}