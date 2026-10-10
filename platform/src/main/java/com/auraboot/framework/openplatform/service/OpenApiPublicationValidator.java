package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.common.constant.StatusConstants;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves publication declarations against the current model without granting write authority. */
@Component
public class OpenApiPublicationValidator {
    private final MetaModelService models;

    public OpenApiPublicationValidator(MetaModelService models) {
        this.models = models;
    }

    public ModelDefinition validate(OpenApiResourceContract contract) {
        ModelDefinition model = models.getModelDefinition(contract.modelCode())
                .orElseThrow(() -> invalid("Publication model does not exist"));
        validate(contract, model);
        return model;
    }

    public void validate(OpenApiResourceContract contract, ModelDefinition model) {
        require(model != null && contract.modelCode().equals(model.getCode()), "Publication source mismatch");
        require(StatusConstants.PUBLISHED.equals(model.getStatus()) || StatusConstants.ACTIVE.equals(model.getStatus()),
                "Publication requires an active model");
        ModelCapabilities capabilities = model.getCapabilities();
        require(capabilities != null, "Model capabilities must be declared");
        for (var operation : contract.operations()) {
            boolean enabled = switch (operation) {
                case LIST -> capabilities.isList();
                case GET -> capabilities.isDetail();
                case CREATE -> capabilities.isCreate();
                case UPDATE -> capabilities.isUpdate();
                case DELETE -> capabilities.isDelete();
            };
            require(enabled, "Operation exceeds model capabilities: " + operation);
        }
        boolean writes = contract.operations().stream().anyMatch(Set.of(
                OpenApiResourceContract.Operation.CREATE, OpenApiResourceContract.Operation.UPDATE,
                OpenApiResourceContract.Operation.DELETE)::contains);
        if (writes) {
            require(model.getSourceType() == null || "physical".equals(model.getSourceType()), "Virtual models are read-only");
            require(!"view".equalsIgnoreCase(model.getModelType()), "View models are read-only");
        }
        require(!model.isImmutable() || (!contract.operations().contains(OpenApiResourceContract.Operation.UPDATE)
                && !contract.operations().contains(OpenApiResourceContract.Operation.DELETE)), "Immutable model cannot publish update/delete");
        require(!model.isCommandOnlyCreate() || !contract.operations().contains(OpenApiResourceContract.Operation.CREATE),
                "Command-only model cannot publish generic creation");
        Object commandOnlyDelete = model.getExtension() == null ? null : model.getExtension().get("commandOnlyDelete");
        require(commandOnlyDelete == null || commandOnlyDelete instanceof Boolean, "Invalid model deletion policy");
        require(!Boolean.TRUE.equals(commandOnlyDelete) || !contract.operations().contains(OpenApiResourceContract.Operation.DELETE),
                "Command-only model cannot publish generic deletion");

        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        for (FieldDefinition field : model.getFields() == null ? List.<FieldDefinition>of() : model.getFields()) {
            require(field.getCode() != null && !fields.containsKey(field.getCode()), "Model field definition is ambiguous");
            fields.put(field.getCode(), field);
        }
        for (var entry : contract.fields().entrySet()) {
            var publicField = entry.getValue();
            if ("pid".equals(publicField.source())) continue;
            FieldDefinition source = fields.get(publicField.source());
            require(source != null, "Published source field does not exist: " + entry.getKey());
            require(!source.isTransientField(), "Transient fields cannot be published");
            require(!publicField.writable() || !source.isComputedReadonly(), "Computed field is read-only");
            require(!publicField.writable() || !Boolean.TRUE.equals(source.getPrimaryKey()), "Record identity is read-only");
            require(!publicField.sortable() || capabilities.canSortBy(source.getCode()), "Sort exceeds model capabilities");
            require(publicField.operators().isEmpty() || capabilities.canFilterBy(source.getCode()), "Filter exceeds model capabilities");
            require(typeMatches(publicField.type(), source.getDataType()), "Public type does not match model field: " + entry.getKey());
            require(!"reference".equalsIgnoreCase(source.getDataType()) || source.getRefTarget() != null,
                    "Reference source binding must be declared");
            if (source.getRefTarget() != null) {
                require(publicField.type() == OpenApiResourceContract.Type.PID && publicField.referenceResource() != null,
                        "Reference fields require a public PID resource binding");
                String valueField = source.getRefTarget().getValueField();
                require(valueField == null || "pid".equals(valueField), "Internal-ID references cannot be published");
            }
        }
        if (contract.operations().contains(OpenApiResourceContract.Operation.CREATE)) {
            for (var source : fields.values()) {
                if (!Boolean.TRUE.equals(source.getRequired()) || Boolean.TRUE.equals(source.getPrimaryKey())
                        || source.getDefaultValue() != null || source.isComputedReadonly() || source.isTransientField()) continue;
                boolean declared = contract.fields().values().stream().anyMatch(field ->
                        source.getCode().equals(field.source()) && field.writable() && field.required() && !field.nullable());
                require(declared, "Required model input is absent from the create contract");
            }
        }
    }

    private boolean typeMatches(OpenApiResourceContract.Type type, String rawType) {
        if (rawType == null) return false;
        String normalized = rawType.toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case STRING -> Set.of("STRING", "TEXT", "ENUM", "AI_TEXT").contains(normalized);
            case PID -> Set.of("STRING", "REFERENCE").contains(normalized);
            case INTEGER -> "INTEGER".equals(normalized);
            case DECIMAL -> "DECIMAL".equals(normalized);
            case BOOLEAN -> "BOOLEAN".equals(normalized);
            case DATE -> "DATE".equals(normalized);
            case DATETIME -> "DATETIME".equals(normalized);
            case OBJECT -> Set.of("JSON", "MONEY").contains(normalized);
            case ARRAY -> "JSON".equals(normalized);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
