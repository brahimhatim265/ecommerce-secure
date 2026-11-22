package ma.ensias.ecommerce.user.security.mtls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The correspondence table: four entries, exact and case-sensitive. */
class ServiceIdentityTest {

    @ParameterizedTest
    @CsvSource({
            "user-service,SERVICE_USER",
            "order-service,SERVICE_ORDER",
            "payment-service,SERVICE_PAYMENT",
            "authorization-server,SERVICE_AUTH"})
    void eachOfTheFourCommonNamesGivesItsIdentity(String commonName, ServiceIdentity expected) {
        assertThat(ServiceIdentity.fromCommonName(commonName)).contains(expected);
        assertThat(expected.commonName()).isEqualTo(commonName);
    }

    @Test
    void thereAreExactlyFourIdentities() {
        assertThat(ServiceIdentity.values()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rogue-service", "Order-Service", "ORDER-SERVICE", " order-service", "order-service ",
            "order-service.local", "order", "authorization_server"})
    void anyOtherCommonNameGivesNoIdentity(String commonName) {
        assertThat(ServiceIdentity.fromCommonName(commonName)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullOrEmptyGivesNoIdentity(String commonName) {
        assertThat(ServiceIdentity.fromCommonName(commonName)).isEmpty();
    }
}
