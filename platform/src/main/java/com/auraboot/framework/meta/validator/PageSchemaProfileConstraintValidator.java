package com.auraboot.framework.meta.validator;

import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.plugin.validation.PageSchemaValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/** DTO checks cannot resolve omitted update fields; the save service checks the merged entity. */
public class PageSchemaProfileConstraintValidator
        implements ConstraintValidator<ValidPageSchemaProfile, Object> {
    private final PageSchemaValidator profiles;

    public PageSchemaProfileConstraintValidator() {
        this(new PageSchemaValidator());
    }

    @Autowired
    public PageSchemaProfileConstraintValidator(PageSchemaValidator profiles) {
        this.profiles = profiles;
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        boolean valid;
        if (value instanceof PageSchemaCreateRequest request) {
            valid = profiles.isAuthoringKindAllowed(request.getKind(), request.getProfile());
        } else if (value instanceof PageSchemaUpdateRequest request) {
            valid = request.getKind() == null || (request.getProfile() == null
                    ? profiles.isKnownAuthoringKind(request.getKind())
                    : profiles.isAuthoringKindAllowed(request.getKind(), request.getProfile()));
        } else {
            return true;
        }
        if (!valid) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Invalid page kind for render profile")
                    .addPropertyNode("kind").addConstraintViolation();
        }
        return valid;
    }
}
