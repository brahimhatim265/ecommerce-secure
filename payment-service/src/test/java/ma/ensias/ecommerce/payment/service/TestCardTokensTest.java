package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.entity.FailureReason;
import ma.ensias.ecommerce.payment.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TestCardTokensTest {

    @ParameterizedTest(name = "{0} -> {1} {2} ****{3} {4}")
    @CsvSource(value = {
            "tok_visa_approved,SUCCEEDED,VISA,4242,NULL",
            "tok_mastercard_approved,SUCCEEDED,MASTERCARD,4444,NULL",
            "tok_declined_insufficient_funds,FAILED,VISA,9995,INSUFFICIENT_FUNDS",
            "tok_declined_expired_card,FAILED,VISA,0069,EXPIRED_CARD",
            "tok_declined_generic,FAILED,VISA,0002,CARD_DECLINED"})
    void everyFixedTokenGivesItsDocumentedOutcome(String token, PaymentStatus status, String brand, String last4,
                                                  String reason) {
        CardOutcome outcome = TestCardTokens.findFixed(token).orElseThrow();

        assertThat(outcome.status()).isEqualTo(status);
        assertThat(outcome.brand()).isEqualTo(brand);
        assertThat(outcome.last4()).isEqualTo(last4);
        assertThat(outcome.failureReason())
                .isEqualTo("NULL".equals(reason) ? null : FailureReason.valueOf(reason));
    }

    @Test
    void randomTokenIsKnownButNotFixed() {
        assertThat(TestCardTokens.isRandom("tok_random")).isTrue();
        assertThat(TestCardTokens.isKnown("tok_random")).isTrue();
        assertThat(TestCardTokens.findFixed("tok_random")).isEmpty();
    }

    @Test
    void unknownTokenIsNotKnown() {
        assertThat(TestCardTokens.isKnown("tok_unknown")).isFalse();
        assertThat(TestCardTokens.findFixed("tok_unknown")).isEmpty();
        assertThat(TestCardTokens.isKnown(null)).isFalse();
    }

    @Test
    void catalogueContainsSixTokens() {
        assertThat(TestCardTokens.allTokens()).containsExactly("tok_visa_approved", "tok_mastercard_approved",
                "tok_declined_insufficient_funds", "tok_declined_expired_card", "tok_declined_generic",
                "tok_random");
    }
}
