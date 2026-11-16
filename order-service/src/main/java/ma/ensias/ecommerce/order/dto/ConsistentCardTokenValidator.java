package ma.ensias.ecommerce.order.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ma.ensias.ecommerce.order.entity.PaymentMethod;

/**
 * Cross-field validation reported on the {@code cardToken} property. It only checks presence: whether the token
 * exists is the business of payment-service.
 */
public class ConsistentCardTokenValidator implements ConstraintValidator<ConsistentCardToken, CreateOrderRequest> {

    @Override
    public boolean isValid(CreateOrderRequest request, ConstraintValidatorContext context) {
        if (request == null || request.paymentMethod() == null) {
            return true; // a missing method is reported by its own @NotNull
        }
        boolean tokenPresent = request.cardToken() != null;
        boolean consistent = (request.paymentMethod() == PaymentMethod.CARD) == tokenPresent;
        if (!consistent) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("cardToken").addConstraintViolation();
        }
        return consistent;
    }
}
