package com.auraboot.module.meta.excel;

import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves and validates the fail-closed import policy for one model. */
@Service
@RequiredArgsConstructor
public class ExcelImportPolicyResolver {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MetaModelService metaModelService;
    private final CommandService commandService;

    public ExcelImportPolicy resolve(String modelCode) {
        ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                .orElseThrow(() -> new BusinessException("Model not found: " + modelCode));
        Map<String, Object> extension = model.getExtension();
        Map<String, Object> raw = extension == null ? null : asMap(extension.get("importPolicy"));
        boolean enabled = raw != null && Boolean.TRUE.equals(raw.get("enabled"));

        Set<String> modes = normalizedStrings(raw == null ? null : raw.get("modes"));
        if (modes.isEmpty()) {
            modes = Set.of("insert");
        }
        if (!modes.stream().allMatch(Set.of("insert", "update")::contains)) {
            throw new BusinessException("Unsupported import mode configured for model: " + modelCode);
        }

        List<String> updateKeys = new ArrayList<>(normalizedStrings(raw == null ? null : raw.get("updateKeys")));
        Set<String> modelFields = new LinkedHashSet<>();
        for (FieldDefinition field : safeFields(modelCode)) {
            modelFields.add(field.getCode());
        }
        for (String key : updateKeys) {
            if (!modelFields.contains(key)) {
                throw new BusinessException("Import upsert key is not a model field: " + key);
            }
        }
        if (modes.contains("update") && updateKeys.isEmpty()) {
            throw new BusinessException("UPDATE import requires at least one configured match key: " + modelCode);
        }

        Map<String, String> crud = commandService.resolveCrudCommands(modelCode);
        String createCommand = crud.get("create");
        String updateCommand = crud.get("update");
        if (model.isCommandOnlyCreate() && createCommand == null) {
            throw new BusinessException("Import policy requires a create command for command-only model: " + modelCode);
        }
        if (modes.contains("update") && createCommand != null && updateCommand == null) {
            throw new BusinessException("UPDATE import requires an update command: " + modelCode);
        }

        Set<String> createFields = commandFields(createCommand, "inputFields");
        Set<String> createAutoSetFields = commandMapKeys(createCommand, "autoSetFields");
        Set<String> updateFields = commandFields(updateCommand, "inputFields");
        if (createCommand == null) {
            createFields = importableModelFields(modelCode);
        }
        if (updateCommand == null) {
            updateFields = importableModelFields(modelCode);
        }

        return ExcelImportPolicy.builder()
                .modelCode(modelCode)
                .enabled(enabled)
                .modes(modes)
                .updateKeys(updateKeys)
                .createCommand(createCommand)
                .updateCommand(updateCommand)
                .createFields(createFields)
                .createAutoSetFields(createAutoSetFields)
                .updateFields(updateFields)
                .build();
    }

    public ExcelImportPolicy requireEnabled(String modelCode) {
        ExcelImportPolicy policy = resolve(modelCode);
        if (!policy.isEnabled()) {
            throw new BusinessException("Excel import is not enabled for model: " + modelCode);
        }
        return policy;
    }

    public void validateMode(ExcelImportPolicy policy, String mode, String matchKey) {
        mode = mode == null || mode.isBlank() ? "insert" : mode.toLowerCase(Locale.ROOT);
        if (!policy.supports(mode)) {
            throw new BusinessException("Import mode is not enabled for model: " + mode);
        }
        if ("update".equals(mode) && !policy.getUpdateKeys().contains(matchKey)) {
            throw new BusinessException("Import match key is not allowed for model: " + matchKey);
        }
    }

    private Set<String> commandFields(String commandCode, String property) {
        if (commandCode == null) {
            return Set.of();
        }
        CommandDefinitionDTO command = commandService.findByCode(commandCode);
        if (command.getExecutionConfig() == null || command.getExecutionConfig().isBlank()) {
            return Set.of();
        }
        Map<String, Object> config = JsonUtil.parse(command.getExecutionConfig(), MAP_TYPE);
        return normalizedStrings(config.get(property));
    }

    private Set<String> commandMapKeys(String commandCode, String property) {
        if (commandCode == null) {
            return Set.of();
        }
        CommandDefinitionDTO command = commandService.findByCode(commandCode);
        if (command.getExecutionConfig() == null || command.getExecutionConfig().isBlank()) {
            return Set.of();
        }
        Map<String, Object> config = JsonUtil.parse(command.getExecutionConfig(), MAP_TYPE);
        Map<String, Object> values = asMap(config.get(property));
        return values == null ? Set.of() : normalizedStrings(values.keySet());
    }

    private Set<String> importableModelFields(String modelCode) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (FieldDefinition field : safeFields(modelCode)) {
            if (!SystemFieldConstants.ALL_INFRASTRUCTURE.contains(field.getCode())
                    && !field.isPrimaryKey()
                    && !field.isComputedReadonly()
                    && !field.isVirtual()) {
                fields.add(field.getCode());
            }
        }
        return fields;
    }

    private List<FieldDefinition> safeFields(String modelCode) {
        List<FieldDefinition> fields = metaModelService.getModelFields(modelCode);
        return fields == null ? List.of() : fields;
    }

    private static Set<String> normalizedStrings(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
