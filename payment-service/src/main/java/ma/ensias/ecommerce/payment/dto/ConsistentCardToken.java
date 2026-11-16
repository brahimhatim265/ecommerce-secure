package ma.ensias.ecommerce.payment.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The card token is required when the method is CARD and forbidden when it is CASH_ON_DELIVERY. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConsistentCardTokenValidator.class)
public @interface ConsistentCardToken {

    String message() default "cardToken is required when method is CARD and must be absent when method is CASH_ON_DELIVERY";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
