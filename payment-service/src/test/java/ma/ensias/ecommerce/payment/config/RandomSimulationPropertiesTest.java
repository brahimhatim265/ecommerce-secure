package ma.ensias.ecommerce.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RandomSimulationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SimulationConfig.class);

    @Test
    void defaultsAre60_20_10_10WithoutSeed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RandomSimulationProperties properties = context.getBean(RandomSimulationProperties.class);
            assertThat(properties.seed()).isNull();
            assertThat(properties.weights().success()).isEqualTo(60);
            assertThat(properties.weights().insufficientFunds()).isEqualTo(20);
            assertThat(properties.weights().cardDeclined()).isEqualTo(10);
            assertThat(properties.weights().expiredCard()).isEqualTo(10);
        });
    }

    @Test
    void customValuesAndSeedAreBound() {
        runner.withPropertyValues("payment.simulation.random.seed=99",
                        "payment.simulation.random.weights.success=1",
                        "payment.simulation.random.weights.insufficient-funds=2",
                        "payment.simulation.random.weights.card-declined=3",
                        "payment.simulation.random.weights.expired-card=4")
                .run(context -> {
                    RandomSimulationProperties properties = context.getBean(RandomSimulationProperties.class);
                    assertThat(properties.seed()).isEqualTo(99L);
                    assertThat(properties.weights().total()).isEqualTo(10);
                });
    }

    @Test
    void negativeWeightMakesTheStartupFail() {
        runner.withPropertyValues("payment.simulation.random.weights.success=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allZeroWeightsMakeTheStartupFail() {
        runner.withPropertyValues("payment.simulation.random.weights.success=0",
                        "payment.simulation.random.weights.insufficient-funds=0",
                        "payment.simulation.random.weights.card-declined=0",
                        "payment.simulation.random.weights.expired-card=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidWeightsAreRejectedByTheConstructorToo() {
        assertThatThrownBy(() -> new RandomSimulationProperties.Weights(-5, 10, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RandomSimulationProperties.Weights(0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
