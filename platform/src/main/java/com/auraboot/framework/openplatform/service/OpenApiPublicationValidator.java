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
    private final OpenApiCapabilityRegistry fixedRoutes;

    public OpenApiPublicationValidator(MetaModelService models) {
        this(models, List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenApiPublicationValidator(MetaModelService models, List<OpenApiCapabilityContributor> contributors) {
        this.models = models;
        this.fixedRoutes = new OpenApiCapabilityRegistry(contributors);
    }

    public ModelDefinition validate(OpenApiResourceContract contract) {
        ModelDefinition model = models.getModelDefinition(contract.modelCode())
                .orElseThrow(() -> invalid("Publication model does not exist"));
        validate(contract, model);
        return model;
    }

    public record SourceField(String code, String name, Set<OpenApiResourceContract.Type> types,
                              boolean writable, boolean required, boolean sortable, boolean filterable,
                              String referenceModel, boolean referencePid, boolean eventReadable) { }
    public record SourceView(String modelCode, String name, Set<OpenApiResourceContract.Operation> operations,
                             boolean eventsAllowed, List<SourceField> fields) { }

    /** Advisory editor metadata from the same runtime source as publication validation. */
    public SourceView describe(String code) {
        var model = models.getModelDefinition(code).orElseThrow(() -> invalid("Publication model does not exist"));
        require(StatusConstants.PUBLISHED.equals(model.getStatus()) || StatusConstants.ACTIVE.equals(model.getStatus()),
                "Publication requires an active model");
        var caps = model.getCapabilities();
        require(caps != null, "Model capabilities must be declared");
        boolean physical = (model.getSourceType() == null || "physical".equals(model.getSourceType()))
                && !"view".equalsIgnoreCase(model.getModelType());
        Object commandDelete = model.getExtension() == null ? null : model.getExtension().get("commandOnlyDelete");
        require(commandDelete == null || commandDelete instanceof Boolean, "Invalid model deletion policy");
        var operations = java.util.EnumSet.noneOf(OpenApiResourceContract.Operation.class);
        if (caps.isList()) operations.add(OpenApiResourceContract.Operation.LIST);
        if (caps.isDetail()) operations.add(OpenApiResourceContract.Operation.GET);
        if (physical && caps.isCreate() && !model.isCommandOnlyCreate()) operations.add(OpenApiResourceContract.Operation.CREATE);
        if (physical && caps.isUpdate() && !model.isImmutable()) operations.add(OpenApiResourceContract.Operation.UPDATE);
        if (physical && caps.isDelete() && !model.isImmutable() && !Boolean.TRUE.equals(commandDelete)) operations.add(OpenApiResourceContract.Operation.DELETE);
        Set<String> internal = Set.of("id", "pid", "tenant_id", "tenantId", "created_by", "updated_by", "row_version", "deleted_flag", "_expectedVersion");
        var fields = (model.getFields() == null ? List.<FieldDefinition>of() : model.getFields()).stream()
                .filter(field -> field.getCode() != null && !internal.contains(field.getCode()) && !field.isTransientField())
                .map(field -> {
                    var types = java.util.EnumSet.noneOf(OpenApiResourceContract.Type.class);
                    for (var type : OpenApiResourceContract.Type.values()) if (typeMatches(type, field.getDataType())) types.add(type);
                    var ref = field.getRefTarget();
                    if (ref != null) { types.clear(); types.add(OpenApiResourceContract.Type.PID); }
                    return new SourceField(field.getCode(), field.getDisplayName(), Set.copyOf(types),
                            !field.isComputedReadonly() && !Boolean.TRUE.equals(field.getPrimaryKey())
                                    && !Set.of("created_at", "updated_at").contains(field.getCode()),
                            Boolean.TRUE.equals(field.getRequired()) && field.getDefaultValue() == null,
                            caps.canSortBy(field.getCode()), caps.canFilterBy(field.getCode()),
                            ref == null ? null : ref.getTargetEntity(),
                            ref == null || ref.getValueField() == null || "pid".equals(ref.getValueField()), !field.isJsonbVirtual());
                }).filter(field -> !field.types().isEmpty()).toList();
        return new SourceView(model.getCode(), model.getDisplayName(), Set.copyOf(operations), physical, fields);
    }

    public void validate(OpenApiResourceContract contract, ModelDefinition model) {
        fixedRoutes.requireAvailableResourceRoutes(contract);
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
        if (writes || !contract.events().isEmpty()) {
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
            publicField.properties().values().forEach(this::requireNoNestedReference);
            if (publicField.items() != null) requireNoNestedReference(publicField.items());
            if ("pid".equals(publicField.source())) continue;
            FieldDefinition source = fields.get(publicField.source());
            require(source != null, "Published source field does not exist: " + entry.getKey());
            require(!source.isTransientField(), "Transient fields cannot be published");
            require(contract.events().isEmpty() || !publicField.readable() || !source.isJsonbVirtual(),
                    "Published events require physical source fields");
            require(!publicField.writable() || !source.isComputedReadonly(), "Computed field is read-only");
            require(!publicField.writable() || !Boolean.TRUE.equals(source.getPrimaryKey()), "Record identity is read-only");
            require(!publicField.writable() || !Set.of("created_at", "updated_at").contains(source.getCode()),
                    "Model audit timestamps are read-only");
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
                require(source.getRefTarget().getTargetEntity() != null && !source.getRefTarget().getTargetEntity().isBlank(),
                        "Reference target model must be declared");
            } else {
                require(publicField.referenceResource() == null, "Public reference requires a model reference binding");
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

    public String referenceTargetModel(OpenApiResourceContract contract, OpenApiResourceContract.Field field) {
        var model = validate(contract);
        var source = model.getFields().stream().filter(value -> field.source().equals(value.getCode())).findFirst()
                .orElseThrow(() -> invalid("Reference source field unavailable"));
        require(field.referenceResource() != null && source.getRefTarget() != null,
                "Public reference binding required");
        return source.getRefTarget().getTargetEntity();
    }

    private void requireNoNestedReference(OpenApiResourceContract.Field field) {
        require(field.referenceResource() == null, "Nested relationship references are not supported");
        field.properties().values().forEach(this::requireNoNestedReference);
        if (field.items() != null) requireNoNestedReference(field.items());
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
