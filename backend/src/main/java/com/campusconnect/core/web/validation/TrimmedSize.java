package com.campusconnect.core.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * express-validator's {@code .isString().trim().isLength({min, max})}: the length of the trimmed text must be in
 * range. {@code null} fails unless {@code optional} (then only present values are checked).
 */
@Documented
@Constraint(validatedBy = TrimmedSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TrimmedSize {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    boolean optional() default false;

    String message() default "Invalid value";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
