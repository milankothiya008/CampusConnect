package com.campusconnect.feature.result;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * express-validator's {@code isInt({min, max})} on a JSON number: a whole number in range. {@code null} passes (the
 * rules are {@code optional({values: "null"})}). Declared on {@link Number} fields so that 2.5 is refused like in
 * Node instead of being silently truncated to 2, as binding it to an {@code Integer} would.
 */
@Documented
@Constraint(validatedBy = WholeNumber.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface WholeNumber {

    long min() default Long.MIN_VALUE;

    long max() default Long.MAX_VALUE;

    String message() default "Invalid value";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<WholeNumber, Number> {

        private long min;
        private long max;

        @Override
        public void initialize(WholeNumber annotation) {
            this.min = annotation.min();
            this.max = annotation.max();
        }

        @Override
        public boolean isValid(Number value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            double number = value.doubleValue();
            return Double.isFinite(number) && Math.rint(number) == number && number >= min && number <= max;
        }
    }
}
