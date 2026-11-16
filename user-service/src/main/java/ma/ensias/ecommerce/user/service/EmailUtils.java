package ma.ensias.ecommerce.user.service;

import java.util.Locale;

/** Email normalization (single place) and masking for logs. */
public final class EmailUtils {

    private static final String MASK = "***";

    private EmailUtils() {
    }

    /** Trims surrounding spaces and lower-cases the address; null stays null. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Masks an address for logging, e.g. {@code hamza.belhaj@example.com} becomes {@code h***@example.com}. */
    public static String mask(String email) {
        if (email == null) {
            return MASK;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return MASK;
        }
        return email.charAt(0) + MASK + email.substring(at);
    }
}
