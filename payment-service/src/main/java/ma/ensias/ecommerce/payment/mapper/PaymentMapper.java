package ma.ensias.ecommerce.payment.mapper;

import ma.ensias.ecommerce.payment.dto.PaymentResponse;
import ma.ensias.ecommerce.payment.entity.Payment;
import org.springframework.stereotype.Component;

/** Hand-written entity to DTO conversion. */
@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getReference(), payment.getOrderId(),
                payment.getOwnerId(), payment.getAmount(), payment.getCurrency(), payment.getMethod(),
                payment.getStatus(), payment.getFailureReason(), payment.getCardBrand(), payment.getCardLast4(),
                payment.getCreatedAt(), payment.getUpdatedAt(), payment.getCollectedAt());
    }
}
