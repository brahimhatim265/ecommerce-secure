package ma.ensias.ecommerce.auth.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Computes the values of the project-specific token claims {@code name} and {@code roles}.
 * <p>
 * Pure functions, no Spring context: the claim contract is documented in {@code docs/contrat-jwt.md}.
 */
public final class TokenClaimValues {

    private static final String ROLE_PREFIX = "ROLE_";

    private TokenClaimValues() {
    }

    /**
     * Joins first and last name with a single space. Extra whitespace is removed; if one part is
     * blank, the other one is used alone. Returns empty when both parts are blank, so that no
     * empty {@code name} claim is issued.
     */
    public static Optional<String> fullName(String firstName, String lastName) {
        String joined = (nullToEmpty(firstName) + " " + nullToEmpty(lastName))
                .replaceAll("\\p{javaWhitespace}+", " ")
                .strip();
        return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
    }

    /**
     * Exports the {@code ROLE_*} authorities as plain role names: prefix removed, sorted, without
     * duplicates. Any other authority is ignored. Always returns a list (possibly empty).
     */
    public static List<String> roles(Collection<? extends GrantedAuthority> authorities) {
        Objects.requireNonNull(authorities, "authorities");
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .filter(role -> !role.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}