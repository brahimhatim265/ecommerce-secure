package ma.ensias.ecommerce.payment.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ma.ensias.ecommerce.payment.entity.PaymentMethod;

/** Cross-field validation reported on the {@code cardToken} property. */
public class ConsistentCardTokenValidator implements ConstraintValidator<ConsistentCardToken, CreatePaymentRequest> {

    @Override
    public boolean isValid(CreatePaymentRequest request, ConstraintValidatorContext context) {
        if (request == null || request.method() == null) {
            return true; // a missing method is reported by its own @NotNull
        }
        boolean tokenPresent = request.cardToken() != null;
        boolean consistent = (request.method() == PaymentMethod.CARD) == tokenPresent;
        if (!consistent) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("cardToken").addConstraintViolation();
        }
        return consistent;
    }
}
