package com.auraboot.framework.meta.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Resolves authoring kind vocabulary from trusted host render-profile registrations. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PageSchemaProfileConstraintValidator.class)
public @interface ValidPageSchemaProfile {
    String message() default "Invalid page kind for render profile";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
