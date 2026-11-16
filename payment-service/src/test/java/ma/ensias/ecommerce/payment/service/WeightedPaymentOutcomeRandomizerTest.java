package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.config.RandomSimulationProperties;
import ma.ensias.ecommerce.payment.config.RandomSimulationProperties.Weights;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedPaymentOutcomeRandomizerTest {

    private static final int DRAWS = 20_000;

    private static RandomSimulationProperties properties(Long seed, int success, int funds, int declined,
                                                         int expired) {
        return new RandomSimulationProperties(seed, new Weights(success, funds, declined, expired));
    }

    @Test
    void sameSeedGivesTheSameSequence() {
        WeightedPaymentOutcomeRandomizer first = new WeightedPaymentOutcomeRandomizer(properties(42L, 60, 20, 10, 10));
        WeightedPaymentOutcomeRandomizer second = new WeightedPaymentOutcomeRandomizer(properties(42L, 60, 20, 10, 10));

        List<CardOutcome> a = new ArrayList<>();
        List<CardOutcome> b = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            a.add(first.draw());
            b.add(second.draw());
        }
        assertThat(a).isEqualTo(b);
    }

    @Test
    void proportionsFollowTheDefaultWeights() {
        WeightedPaymentOutcomeRandomizer randomizer =
                new WeightedPaymentOutcomeRandomizer(properties(12345L, 60, 20, 10, 10));

        Map<CardOutcome, Integer> counts = count(randomizer);

        double success = share(counts, TestCardTokens.VISA_APPROVED) + share(counts, TestCardTokens.MASTERCARD_APPROVED);
        assertThat(success).isBetween(0.57, 0.63);
        assertThat(share(counts, TestCardTokens.INSUFFICIENT_FUNDS)).isBetween(0.17, 0.23);
        assertThat(share(counts, TestCardTokens.CARD_DECLINED)).isBetween(0.07, 0.13);
        assertThat(share(counts, TestCardTokens.EXPIRED_CARD)).isBetween(0.07, 0.13);
        // the success outcome is split between the two approved cards
        assertThat(share(counts, TestCardTokens.VISA_APPROVED)).isBetween(0.27, 0.33);
        assertThat(share(counts, TestCardTokens.MASTERCARD_APPROVED)).isBetween(0.27, 0.33);
    }

    @Test
    void weightsAreNormalizedAndDoNotHaveToTotal100() {
        WeightedPaymentOutcomeRandomizer randomizer =
                new WeightedPaymentOutcomeRandomizer(properties(7L, 3, 1, 0, 0));

        Map<CardOutcome, Integer> counts = count(randomizer);

        double success = share(counts, TestCardTokens.VISA_APPROVED) + share(counts, TestCardTokens.MASTERCARD_APPROVED);
        assertThat(success).isBetween(0.72, 0.78);
        assertThat(share(counts, TestCardTokens.INSUFFICIENT_FUNDS)).isBetween(0.22, 0.28);
        assertThat(share(counts, TestCardTokens.CARD_DECLINED)).isZero();
        assertThat(share(counts, TestCardTokens.EXPIRED_CARD)).isZero();
    }

    @Test
    void aSingleNonZeroWeightAlwaysGivesThatOutcome() {
        WeightedPaymentOutcomeRandomizer randomizer =
                new WeightedPaymentOutcomeRandomizer(properties(null, 0, 0, 0, 5));

        for (int i = 0; i < 100; i++) {
            assertThat(randomizer.draw()).isEqualTo(TestCardTokens.EXPIRED_CARD);
        }
    }

    private static Map<CardOutcome, Integer> count(PaymentOutcomeRandomizer randomizer) {
        Map<CardOutcome, Integer> counts = new HashMap<>();
        for (int i = 0; i < DRAWS; i++) {
            counts.merge(randomizer.draw(), 1, Integer::sum);
        }
        return counts;
    }

    private static double share(Map<CardOutcome, Integer> counts, CardOutcome outcome) {
        return counts.getOrDefault(outcome, 0) / (double) DRAWS;
    }
}
