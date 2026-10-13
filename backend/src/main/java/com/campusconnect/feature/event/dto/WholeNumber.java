package com.campusconnect.feature.event.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * express-validator's {@code isInt({min, max})} on a loosely typed JSON value: a whole number, or a string of one,
 * in range. Unlike binding to an {@code Integer}, 4.5 is refused instead of being truncated to 4. Null is valid
 * (combine with {@code @NotNull} for required fields).
 */
@Documented
@Constraint(validatedBy = WholeNumberValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface WholeNumber {

    long min();

    long max();

    String message() default "Invalid value";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
