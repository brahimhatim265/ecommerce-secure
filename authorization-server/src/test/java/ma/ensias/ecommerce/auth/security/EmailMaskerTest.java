package ma.ensias.ecommerce.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "hamza.belhaj@example.com, h***@example.com",
            "a@example.com, a***@example.com",
            "admin@example.com, a***@example.com"
    })
    void keepsOnlyTheFirstLetterAndTheDomain(String email, String expected) {
        assertThat(EmailMasker.mask(email)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "no-at-sign", "@example.com"})
    void unusableInputBecomesStars(String email) {
        assertThat(EmailMasker.mask(email)).isEqualTo("***");
    }

    @Test
    void removesControlCharacters() {
        assertThat(EmailMasker.mask("hamza@exa\nmple.com")).isEqualTo("h***@example.com");
    }
}