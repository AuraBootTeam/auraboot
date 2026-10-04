package com.auraboot.framework.meta.validator;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.plugin.validation.PageSchemaValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;

/** Checks the effective page state consistently before authoring persistence and publication. */
@Component
public class PageSchemaAuthoringProfileValidator {
    private final PageSchemaValidator profiles;
    private final ObjectMapper objectMapper;

    public PageSchemaAuthoringProfileValidator(PageSchemaValidator profiles, ObjectMapper objectMapper) {
        this.profiles = profiles;
        this.objectMapper = objectMapper;
    }

    public void validate(PageSchema page) {
        List<Object> blocks = null;
        if (page.getBlocks() != null) {
            try {
                blocks = objectMapper.readValue(page.getBlocks(), new TypeReference<List<Object>>() { });
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "Invalid page blocks JSON");
            }
        }
        List<String> errors = profiles.authoringProfileErrors(page.getKind(), page.getProfile(), blocks);
        if (!errors.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, String.join("; ", errors));
        }
    }
}
