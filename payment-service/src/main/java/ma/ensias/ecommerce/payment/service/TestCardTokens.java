package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.entity.FailureReason;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Catalogue of the public test card tokens (sandbox mode, like Stripe's test mode).
 * The token name decides the outcome; a fixed token always gives the same result.
 * {@link #RANDOM} is resolved by a {@link PaymentOutcomeRandomizer}, not by this class.
 */
public final class TestCardTokens {

    public static final String VISA_APPROVED_TOKEN = "tok_visa_approved";
    public static final String MASTERCARD_APPROVED_TOKEN = "tok_mastercard_approved";
    public static final String INSUFFICIENT_FUNDS_TOKEN = "tok_declined_insufficient_funds";
    public static final String EXPIRED_CARD_TOKEN = "tok_declined_expired_card";
    public static final String GENERIC_DECLINE_TOKEN = "tok_declined_generic";
    public static final String RANDOM = "tok_random";

    public static final CardOutcome VISA_APPROVED =
            new CardOutcome(PaymentStatus.SUCCEEDED, "VISA", "4242", null);
    public static final CardOutcome MASTERCARD_APPROVED =
            new CardOutcome(PaymentStatus.SUCCEEDED, "MASTERCARD", "4444", null);
    public static final CardOutcome INSUFFICIENT_FUNDS =
            new CardOutcome(PaymentStatus.FAILED, "VISA", "9995", FailureReason.INSUFFICIENT_FUNDS);
    public static final CardOutcome EXPIRED_CARD =
            new CardOutcome(PaymentStatus.FAILED, "VISA", "0069", FailureReason.EXPIRED_CARD);
    public static final CardOutcome CARD_DECLINED =
            new CardOutcome(PaymentStatus.FAILED, "VISA", "0002", FailureReason.CARD_DECLINED);

    private static final Map<String, CardOutcome> FIXED_TOKENS;

    static {
        Map<String, CardOutcome> tokens = new LinkedHashMap<>();
        tokens.put(VISA_APPROVED_TOKEN, VISA_APPROVED);
        tokens.put(MASTERCARD_APPROVED_TOKEN, MASTERCARD_APPROVED);
        tokens.put(INSUFFICIENT_FUNDS_TOKEN, INSUFFICIENT_FUNDS);
        tokens.put(EXPIRED_CARD_TOKEN, EXPIRED_CARD);
        tokens.put(GENERIC_DECLINE_TOKEN, CARD_DECLINED);
        FIXED_TOKENS = Collections.unmodifiableMap(tokens);
    }

    private TestCardTokens() {
    }

    /** Outcome of a fixed token; empty for {@link #RANDOM} and for unknown tokens. */
    public static Optional<CardOutcome> findFixed(String token) {
        return Optional.ofNullable(FIXED_TOKENS.get(token));
    }

    public static boolean isRandom(String token) {
        return RANDOM.equals(token);
    }

    /** True for the fixed tokens and for {@link #RANDOM}. */
    public static boolean isKnown(String token) {
        return isRandom(token) || FIXED_TOKENS.containsKey(token);
    }

    /** All tokens of the catalogue, in documentation order. */
    public static Set<String> allTokens() {
        Set<String> all = new LinkedHashSet<>(FIXED_TOKENS.keySet());
        all.add(RANDOM);
        return Collections.unmodifiableSet(all);
    }
}
