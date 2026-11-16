package ma.ensias.ecommerce.user.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailUtilsTest {

    @Test
    void normalizeTrimsAndLowerCases() {
        assertThat(EmailUtils.normalize("  Hamza.Belhaj@Example.COM ")).isEqualTo("hamza.belhaj@example.com");
    }

    @Test
    void normalizeKeepsNull() {
        assertThat(EmailUtils.normalize(null)).isNull();
    }

    @Test
    void maskKeepsFirstLetterAndDomain() {
        assertThat(EmailUtils.mask("hamza.belhaj@example.com")).isEqualTo("h***@example.com");
    }

    @Test
    void maskHandlesMalformedValues() {
        assertThat(EmailUtils.mask(null)).isEqualTo("***");
        assertThat(EmailUtils.mask("no-at-sign")).isEqualTo("***");
        assertThat(EmailUtils.mask("@example.com")).isEqualTo("***");
    }
}
