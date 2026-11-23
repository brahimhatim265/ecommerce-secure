package ma.ensias.ecommerce.user.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Test helpers: a decoder that trusts a key generated in memory (and applies the SAME validators as production),
 * a factory of signed tokens, and a request post-processor for tests that only need "some authenticated user".
 */
public final class TestJwtSupport {

    public static final String ISSUER = "https://localhost:9000";
    public static final String SUBJECT = "00000000-0000-4000-8000-000000000001";

    /** The only key the test decoder trusts. */
    public static final RSAKey TRUSTED_KEY = generateKey("trusted-key");

    /** A different key: tokens signed with it must be rejected. */
    public static final RSAKey UNTRUSTED_KEY = generateKey("untrusted-key");

    private TestJwtSupport() {
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate the test RSA key", e);
        }
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TRUSTED_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(TokenValidators.forIssuer(ISSUER));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot build the test decoder", e);
        }
    }

    /** A token that the test decoder accepts: trusted key, expected issuer, not expired, with a roles claim. */
    public static String validToken() {
        return token(TRUSTED_KEY, ISSUER, Instant.now().plus(Duration.ofMinutes(15)), true);
    }

    public static String token(RSAKey signingKey, String issuer, Instant expiresAt, boolean withRolesClaim) {
        Instant issuedAt = expiresAt.minus(Duration.ofMinutes(15));
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(SUBJECT)
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("email", "hamza.belhaj@example.com");
        if (withRolesClaim) {
            claims.claim("roles", List.of("CUSTOMER"));
        }
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
    }

    /** Authenticates a MockMvc request as the demo customer, with the authorities the service gives to a CUSTOMER. */
    public static RequestPostProcessor customer() {
        return TestIdentities.customer();
    }
}
