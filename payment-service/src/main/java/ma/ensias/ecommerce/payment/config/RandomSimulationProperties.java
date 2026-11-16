package ma.ensias.ecommerce.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the {@code tok_random} simulation. Invalid weights (negative, or a null sum) make the
 * application fail at startup.
 *
 * @param seed    optional; when set the draws are reproducible
 * @param weights relative weights of each outcome (normalized, they do not have to total 100)
 */
@ConfigurationProperties(prefix = "payment.simulation.random")
public record RandomSimulationProperties(Long seed, @DefaultValue Weights weights) {

    /** Upper bound per weight, which also keeps the sum far from integer overflow. */
    public static final int MAX_WEIGHT = 1_000_000;

    public record Weights(@DefaultValue("60") int success,
                          @DefaultValue("20") int insufficientFunds,
                          @DefaultValue("10") int cardDeclined,
                          @DefaultValue("10") int expiredCard) {

        public Weights {
            requireInRange("success", success);
            requireInRange("insufficient-funds", insufficientFunds);
            requireInRange("card-declined", cardDeclined);
            requireInRange("expired-card", expiredCard);
            if (success + insufficientFunds + cardDeclined + expiredCard <= 0) {
                throw new IllegalArgumentException("The sum of the simulation weights must be greater than 0");
            }
        }

        public int total() {
            return success + insufficientFunds + cardDeclined + expiredCard;
        }

        private static void requireInRange(String name, int value) {
            if (value < 0 || value > MAX_WEIGHT) {
                throw new IllegalArgumentException(
                        "Simulation weight '" + name + "' must be between 0 and " + MAX_WEIGHT + ", got " + value);
            }
        }
    }
}
