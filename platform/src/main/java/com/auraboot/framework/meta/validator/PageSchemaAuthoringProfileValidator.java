package com.auraboot.framework.meta.validator;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.plugin.validation.PageSchemaValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;

/** Checks effective state without mutating a mapper-cached entity on a denied update. */
@Component
public class PageSchemaAuthoringProfileValidator {
    private final PageSchemaValidator profiles;
    private final ObjectMapper objectMapper;

    public PageSchemaAuthoringProfileValidator(PageSchemaValidator profiles, ObjectMapper objectMapper) {
        this.profiles = profiles;
        this.objectMapper = objectMapper;
    }

    public void validate(PageSchema page) {
        validateValues(page.getKind(), page.getProfile(), readBlocks(page.getBlocks()));
    }

    public void validateUpdate(PageSchema saved, PageSchemaUpdateRequest update) {
        validateValues(update.getKind() == null ? saved.getKind() : update.getKind(),
                update.getProfile() == null ? saved.getProfile() : update.getProfile(),
                update.getBlocks() == null ? readBlocks(saved.getBlocks()) : update.getBlocks());
    }

    private List<Object> readBlocks(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Invalid page blocks JSON");
        }
    }

    private void validateValues(String kind, String profile, List<?> blocks) {
        List<String> errors = profiles.authoringProfileErrors(kind, profile, blocks);
        if (!errors.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, String.join("; ", errors));
        }
    }
}
