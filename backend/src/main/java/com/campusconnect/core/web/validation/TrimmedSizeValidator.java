package com.campusconnect.core.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TrimmedSizeValidator implements ConstraintValidator<TrimmedSize, String> {

    private int min;
    private int max;
    private boolean optional;

    @Override
    public void initialize(TrimmedSize annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
        this.optional = annotation.optional();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return optional;
        }
        int length = value.strip().length();
        return length >= min && length <= max;
    }
}
