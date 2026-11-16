package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.config.RandomSimulationProperties;
import ma.ensias.ecommerce.payment.config.RandomSimulationProperties.Weights;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Default randomizer: weighted draw among success (Visa 4242 or Mastercard 4444, equally likely),
 * insufficient funds, generic decline and expired card. With a configured seed the sequence is reproducible.
 */
@Component
public class WeightedPaymentOutcomeRandomizer implements PaymentOutcomeRandomizer {

    private final Weights weights;
    private final Random random;

    public WeightedPaymentOutcomeRandomizer(RandomSimulationProperties properties) {
        this.weights = properties.weights();
        this.random = properties.seed() == null ? new Random() : new Random(properties.seed());
    }

    @Override
    public CardOutcome draw() {
        int ticket = random.nextInt(weights.total());

        int upperBound = weights.success();
        if (ticket < upperBound) {
            return random.nextBoolean() ? TestCardTokens.VISA_APPROVED : TestCardTokens.MASTERCARD_APPROVED;
        }
        upperBound += weights.insufficientFunds();
        if (ticket < upperBound) {
            return TestCardTokens.INSUFFICIENT_FUNDS;
        }
        upperBound += weights.cardDeclined();
        if (ticket < upperBound) {
            return TestCardTokens.CARD_DECLINED;
        }
        return TestCardTokens.EXPIRED_CARD;
    }
}
