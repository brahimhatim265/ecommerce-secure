package ma.ensias.ecommerce.payment.service;

/** Draws the outcome of a {@code tok_random} payment. Replaceable in tests. */
public interface PaymentOutcomeRandomizer {

    /** Returns one of the outcomes of the test card catalogue. */
    CardOutcome draw();
}
