package ma.ensias.ecommerce.payment.dto;

import java.time.Instant;
import java.util.List;

/** Single error format returned by every service. */
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       List<String> details) {
}
