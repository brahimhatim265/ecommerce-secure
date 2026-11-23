package ma.ensias.ecommerce.payment.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The users of the demo data, as tests need them. Every authentication is built by the PRODUCTION converter
 * ({@link JwtRolesConfig}), so a test that says "customer" or "admin" holds exactly the authorities that a real token
 * with those roles would give.
 */
public final class TestIdentities {

    public static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final UUID OTHER_CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    public static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000008");

    private static final JwtAuthenticationConverter CONVERTER = new JwtRolesConfig().jwtAuthenticationConverter();

    private TestIdentities() {
    }

    /** The authentication that the service builds from a valid token with this subject and these roles. */
    public static Authentication authentication(UUID subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token-of-" + subject)
                .header("alg", "RS256")
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .build();
        return CONVERTER.convert(jwt);
    }

    /** Authenticates a MockMvc request as this user (no decoding involved). */
    public static RequestPostProcessor as(UUID subject, String... roles) {
        return SecurityMockMvcRequestPostProcessors.authentication(authentication(subject, roles));
    }

    public static RequestPostProcessor customer() {
        return as(CUSTOMER_ID, "CUSTOMER");
    }

    public static RequestPostProcessor otherCustomer() {
        return as(OTHER_CUSTOMER_ID, "CUSTOMER");
    }

    public static RequestPostProcessor admin() {
        return as(ADMIN_ID, "ADMIN");
    }

    /** A signed token with this subject and these roles, for the tests that go through the real decoder. */
    public static String bearerToken(UUID subject, String... roles) {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));
        Instant issuedAt = expiresAt.minus(Duration.ofMinutes(15));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TestJwtSupport.ISSUER)
                .subject(subject.toString())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("roles", List.of(roles))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestJwtSupport.TRUSTED_KEY.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(TestJwtSupport.TRUSTED_KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
    }

    public static String adminBearerToken() {
        return bearerToken(ADMIN_ID, "ADMIN");
    }
}
