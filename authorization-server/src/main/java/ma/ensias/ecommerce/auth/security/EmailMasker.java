package ma.ensias.ecommerce.auth.security;

public final class EmailMasker {

    private static final String MASK = "***";

    private EmailMasker() {
    }

    public static String mask(String email) {
        if (email == null) {
            return MASK;
        }
        String cleaned = email.strip().replaceAll("\\p{Cntrl}", "");
        int at = cleaned.indexOf('@');
        if (at < 1) {
            return MASK;
        }
        return cleaned.charAt(0) + MASK + cleaned.substring(at);
    }
}