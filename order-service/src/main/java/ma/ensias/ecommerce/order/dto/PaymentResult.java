package ma.ensias.ecommerce.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The part of a payment-service payment response that order-service needs (tolerant to additional fields). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResult(Long id, String status, String failureReason) {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FAILED = "FAILED";
}
