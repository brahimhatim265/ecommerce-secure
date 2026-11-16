package ma.ensias.ecommerce.payment.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates references such as {@code PAY-7K2M9XQ4TB} ("PAY-" followed by 10 upper-case alphanumerics). */
@Component
public class PaymentReferenceGenerator {

    static final String PREFIX = "PAY-";
    static final int SUFFIX_LENGTH = 10;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder reference = new StringBuilder(PREFIX);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }
}
