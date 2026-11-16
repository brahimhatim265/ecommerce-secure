package ma.ensias.ecommerce.payment.service;

import ma.ensias.ecommerce.payment.dto.CreatePaymentRequest;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprint of the parameters of a payment request, used to detect idempotency-key misuse. */
public final class RequestFingerprint {

    private static final String SEPARATOR = "|";

    private RequestFingerprint() {
    }

    public static String of(CreatePaymentRequest request) {
        String canonical = String.join(SEPARATOR,
                String.valueOf(request.orderId()),
                String.valueOf(request.ownerId()),
                request.amount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                String.valueOf(request.currency()),
                String.valueOf(request.method()),
                request.cardToken() == null ? "" : request.cardToken());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
