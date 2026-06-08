package com.alexandria.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NullOrNotBlankValidator implements ConstraintValidator<NullOrNotBlank, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null is valid (means "no change" in partial-update requests)
        if (value == null) {
            return true;
        }
        return !value.isBlank();
    }
}
