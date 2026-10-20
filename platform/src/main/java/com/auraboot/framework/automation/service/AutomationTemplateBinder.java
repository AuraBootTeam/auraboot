package com.auraboot.framework.automation.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationTemplateParameter;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Resolves typed template references using the caller's normal model/data permissions. */
@Service
@RequiredArgsConstructor
public class AutomationTemplateBinder {
    private final DynamicDataService data;
    private final PermissionEvaluator permissions;
    private final MetaModelService models;

    @SuppressWarnings("unchecked")
    public Map<String, Object> bind(Map<String, Object> flow, List<AutomationTemplateParameter> definitions,
                                     Map<String, String> values) {
        if (definitions == null || definitions.isEmpty()) {
            rejectUnresolved(flow);
            return flow;
        }
        if (definitions.size() > 20 || values == null || values.size() != definitions.size())
            throw invalid("Complete all template parameters before importing");
        Map<String, String> replacements = new LinkedHashMap<>();
        Long member = MetaContext.getCurrentMemberId();
        if (member == null || member <= 0) throw new AccessDeniedException("An active workspace member is required");
        for (var definition : definitions) {
            if (definition == null) throw invalid("Invalid template parameter definition");
            String key = definition.getKey();
            String model = definition.getModelCode();
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !"record".equals(definition.getKind()) || model == null
                    || !model.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                    || replacements.containsKey("{{template." + key + "}}"))
                throw invalid("Invalid template parameter definition");
            String value = values.get(key);
            if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
                throw invalid("Select a record for " + definition.getLabel());
            if (!permissions.canAction(member, model, "read"))
                throw new AccessDeniedException("The selected model cannot be read in this workspace");
            // getById preserves tenant, row-level and field permissions. Never query an unscoped mapper.
            if (models.getModelDefinition(model).isEmpty()) throw invalid("The selected model is unavailable");
            Map<String, Object> record;
            try {
                record = data.getById(model, value);
            } catch (com.auraboot.framework.meta.exception.MetaRecordNotFoundException absent) {
                throw invalid("The selected record is unavailable in this workspace");
            }
            if (record == null || !value.equals(String.valueOf(record.get("pid"))))
                throw invalid("The selected record is unavailable in this workspace");
            String marker = "{{template." + key + "}}";
            if (!contains(flow, marker)) throw invalid("Unused template parameter: " + key);
            replacements.put(marker, value);
        }
        Map<String, Object> bound = (Map<String, Object>) replace(flow, replacements, 0);
        rejectUnresolved(bound);
        return bound;
    }

    private boolean contains(Object input, String marker) {
        return contains(input, marker, 0);
    }

    private boolean contains(Object input, String marker, int depth) {
        if (depth > 40) throw invalid("Template is too deeply nested");
        if (input instanceof String value) return value.contains(marker);
        if (input instanceof Map<?, ?> map) return map.values().stream().anyMatch(value -> contains(value, marker, depth + 1));
        if (input instanceof List<?> list) return list.stream().anyMatch(value -> contains(value, marker, depth + 1));
        return false;
    }

    public void rejectUnresolved(Object flow) {
        if (contains(flow, "{{template."))
            throw invalid("Complete template parameters before saving or enabling");
    }

    private ValidationException invalid(String message) {
        return new ValidationException(ResponseCode.CommonValidationFailed, message);
    }

    private Object replace(Object input, Map<String, String> values, int depth) {
        if (depth > 40) throw invalid("Template is too deeply nested");
        if (input instanceof String text) {
            for (var entry : values.entrySet()) text = text.replace(entry.getKey(), entry.getValue());
            return text;
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), replace(value, values, depth + 1)));
            return copy;
        }
        if (input instanceof List<?> list) return list.stream().map(value -> replace(value, values, depth + 1)).toList();
        return input;
    }
}
