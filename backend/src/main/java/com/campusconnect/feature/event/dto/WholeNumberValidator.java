package com.campusconnect.feature.event.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class WholeNumberValidator implements ConstraintValidator<WholeNumber, Object> {

    private static final Pattern INT = Pattern.compile("^[-+]?[0-9]+$");

    private long min;
    private long max;

    @Override
    public void initialize(WholeNumber annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        long number;
        if (value instanceof Number given) {
            double d = given.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                return false;
            }
            number = (long) d;
        } else if (value instanceof String text && INT.matcher(text).matches()) {
            try {
                number = Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            return false;
        }
        return number >= min && number <= max;
    }
}
