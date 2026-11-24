package ma.ensias.ecommerce.auth.security;

import ma.ensias.ecommerce.auth.dto.VerifiedIdentity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


public record AuthenticatedUser(UUID id, String email, String firstName, String lastName, Set<String> roles)
        implements UserDetails {

    private static final String ROLE_PREFIX = "ROLE_";

    public AuthenticatedUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    public static AuthenticatedUser from(VerifiedIdentity identity) {
        return new AuthenticatedUser(identity.id(), identity.email(), identity.firstName(),
                identity.lastName(), identity.roles());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .sorted()
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return id.toString();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", roles=" + roles + "]";
    }
}