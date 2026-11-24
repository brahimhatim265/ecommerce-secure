package ma.ensias.ecommerce.auth.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class TokenClaimValues {

    private static final String ROLE_PREFIX = "ROLE_";

    private TokenClaimValues() {
    }


    public static Optional<String> fullName(String firstName, String lastName) {
        String joined = (nullToEmpty(firstName) + " " + nullToEmpty(lastName))
                .replaceAll("\\p{javaWhitespace}+", " ")
                .strip();
        return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
    }

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