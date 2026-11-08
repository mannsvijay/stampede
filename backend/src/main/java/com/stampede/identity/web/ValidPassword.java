package com.stampede.identity.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.stampede.identity.user.PasswordPolicy;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Bean Validation constraint for {@link PasswordPolicy}: 12+ characters, at most 72 UTF-8 bytes. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPassword.Validator.class)
@interface ValidPassword {

    String message() default "is not an acceptable password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidPassword, String> {

        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            return PasswordPolicy.violation(password)
                    .map(message -> {
                        context.disableDefaultConstraintViolation();
                        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
                        return false;
                    })
                    .orElse(true);
        }
    }
}
