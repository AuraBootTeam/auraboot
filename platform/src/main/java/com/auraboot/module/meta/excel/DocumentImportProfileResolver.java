package com.auraboot.module.meta.excel;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Resolves the document variant of {@code extension.importPolicy}. */
@Service
@RequiredArgsConstructor
public class DocumentImportProfileResolver {

    private final MetaModelService metaModelService;
    private final CommandService commandService;

    public DocumentImportProfile requireEnabled(String modelCode) {
        ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                .orElseThrow(() -> new BusinessException("Model not found: " + modelCode));
        Map<String, Object> extension = model.getExtension();
        Map<String, Object> policy = extension == null ? null : asMap(extension.get("importPolicy"));
        if (policy == null || !Boolean.TRUE.equals(policy.get("enabled"))
                || !"document".equalsIgnoreCase(string(policy.get("atomicUnit")))) {
            throw new BusinessException("Document import is not enabled for model: " + modelCode);
        }
        Map<String, Object> document = asMap(policy.get("document"));
        if (document == null) {
            throw new BusinessException("Document import configuration is missing: " + modelCode);
        }
        String code = required(policy, "code");
        String command = required(document, "command");
        String headerSheet = required(document, "headerSheet");
        String lineSheet = required(document, "lineSheet");
        String groupBy = required(document, "groupBy");
        String lineGroupField = required(document, "lineGroupField");
        String linesPayloadField = required(document, "linesPayloadField");
        String lineModelCode = required(document, "lineModelCode");
        List<String> headerFields = strings(document.get("headerFields"));
        List<String> lineFields = strings(document.get("lineFields"));
        if (headerFields.isEmpty() || lineFields.isEmpty()
                || !headerFields.contains(groupBy) || !lineFields.contains(lineGroupField)) {
            throw new BusinessException("Document import fields must include both grouping fields: " + code);
        }
        if (new LinkedHashSet<>(headerFields).size() != headerFields.size()
                || new LinkedHashSet<>(lineFields).size() != lineFields.size()) {
            throw new BusinessException("Document import fields must not contain duplicates: " + code);
        }
        requireDeclaredFields(modelCode, headerFields, code);
        requireDeclaredFields(lineModelCode, lineFields, code);
        CommandDefinitionDTO commandDefinition = commandService.findByCode(command);
        if (commandDefinition == null) {
            throw new BusinessException("Document import command not found: " + command);
        }
        if (!modelCode.equals(commandDefinition.getModelCode())
                || !"create".equalsIgnoreCase(commandDefinition.getType())) {
            throw new BusinessException("Document import command must be a create command for model: "
                    + modelCode);
        }
        return DocumentImportProfile.builder()
                .code(code)
                .modelCode(modelCode)
                .command(command)
                .headerSheet(headerSheet)
                .lineSheet(lineSheet)
                .groupBy(groupBy)
                .lineGroupField(lineGroupField)
                .lineModelCode(lineModelCode)
                .linesPayloadField(linesPayloadField)
                .headerFields(headerFields)
                .lineFields(lineFields)
                .requireLines(!Boolean.FALSE.equals(document.get("requireLines")))
                .build();
    }

    private void requireDeclaredFields(String modelCode, List<String> fields, String profileCode) {
        Set<String> declared = new LinkedHashSet<>();
        var definitions = metaModelService.getModelFields(modelCode);
        if (definitions != null) {
            definitions.forEach(field -> declared.add(field.getCode()));
        }
        List<String> unknown = fields.stream().filter(field -> !declared.contains(field)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException("Unknown document import fields in profile " + profileCode
                    + " for model " + modelCode + ": " + String.join(",", unknown));
        }
    }

    private static String required(Map<String, Object> values, String key) {
        String value = string(values.get(key));
        if (value.isBlank()) throw new BusinessException("Document import property is required: " + key);
        return value;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(java.util.Objects::nonNull)
                .map(Object::toString).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
