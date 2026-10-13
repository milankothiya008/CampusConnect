package com.campusconnect.feature.election;

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
 * express-validator's {@code isISO8601()} for an optional date: null passes (pair with {@code @NotNull} when the date
 * is required); with {@code allowEmpty} an empty text passes too ({@code optional({ values: "falsy" })}).
 */
@Documented
@Constraint(validatedBy = IsoDate.Check.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoDate {

    boolean allowEmpty() default false;

    String message() default "Invalid value";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The validator: the text must be a date {@link JsDates} can read. */
    final class Check implements ConstraintValidator<IsoDate, String> {

        private boolean allowEmpty;

        @Override
        public void initialize(IsoDate annotation) {
            this.allowEmpty = annotation.allowEmpty();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || (allowEmpty && value.isEmpty())) {
                return true;
            }
            return JsDates.parse(value) != null;
        }
    }
}
