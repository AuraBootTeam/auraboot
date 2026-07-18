package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionFactDTO;
import com.auraboot.framework.decision.dto.DecisionFactEntityDTO;
import com.auraboot.framework.decision.dto.DecisionFactOptionDTO;
import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.service.DecisionModelFieldService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives the rule-center field catalogue from published model metadata plus validated decision refs.
 */
@Service
@RequiredArgsConstructor
public class DecisionModelFieldServiceImpl implements DecisionModelFieldService {

    private static final Set<String> SUPPORTED_DATA_TYPES = Set.of(
            "string", "text", "integer", "decimal", "boolean", "date", "time",
            "datetime", "duration", "enum", "dict", "reference", "user", "role", "group",
            "department", "collection", "object");

    private final DrtVersionMapper versionMapper;
    private final MetaModelMapper metaModelMapper;
    private final MetaModelService metaModelService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final DictMapper dictMapper;
    private final DictItemMapper dictItemMapper;
    private final FieldPermissionService fieldPermissionService;

    @Override
    public List<DecisionModelFieldDTO> listFields() {
        Long tenantId = requireTenant();
        Map<String, FieldAccumulator> fields = new LinkedHashMap<>();

        collectMetaModelFields(fields);

        for (DrtVersionEntity version : versionMapper.findWithFieldRefs(tenantId)) {
            Map<String, String> dataTypes = new LinkedHashMap<>();
            collectPathDataTypes(version.getContentJson(), dataTypes);

            for (String fieldRef : parseRefs(version.getFieldRefsJson())) {
                FieldRefParts parts = FieldRefParts.parse(fieldRef);
                FieldAccumulator acc = fields.computeIfAbsent(parts.key(), ignored -> new FieldAccumulator(parts));
                acc.refs++;
                acc.decisionCodes.add(version.getDecisionCode());
                acc.dataType = chooseDataType(acc.dataType, dataTypes.get(fieldRef));
            }
        }

        return fields.values().stream()
                .map(FieldAccumulator::toDTO)
                .sorted(Comparator.comparing(DecisionModelFieldDTO::getEntityCode)
                        .thenComparing(DecisionModelFieldDTO::getPath))
                .toList();
    }

    @Override
    public DecisionFactCatalogDTO getFactCatalog(String modelCode) {
        requireTenant();
        DecisionFactCatalogDTO catalog = new DecisionFactCatalogDTO();
        List<DecisionFactEntityDTO> entities = new ArrayList<>();

        for (Model model : metaModelMapper.findCurrentByTenant()) {
            if (model == null || !"published".equalsIgnoreCase(String.valueOf(model.getStatus()))) {
                continue;
            }
            if (hasText(modelCode) && !modelCode.equals(model.getCode())) {
                continue;
            }
            DecisionFactEntityDTO entity = buildModelEntity(model);
            if (!entity.getFacts().isEmpty()) {
                entities.add(entity);
            }
        }

        entities.sort(Comparator.comparing(DecisionFactEntityDTO::getModelCode,
                Comparator.nullsLast(String::compareTo)));
        entities.addAll(sharedContextEntities());
        catalog.setEntities(entities);
        return catalog;
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision model fields not found");
        }
        return tenantId;
    }

    private void collectMetaModelFields(Map<String, FieldAccumulator> fields) {
        for (Model model : metaModelMapper.findCurrentByTenant()) {
            if (model == null || !"published".equalsIgnoreCase(String.valueOf(model.getStatus()))) {
                continue;
            }
            List<MetaFieldDTO> modelFields = modelFieldBindingService.getModelFields(model.getPid());
            String modelLabel = hasText(model.getDisplayName()) ? model.getDisplayName() : model.getCode();
            for (MetaFieldDTO field : modelFields) {
                if (field == null || !Boolean.TRUE.equals(field.getVisible()) || !hasText(field.getCode())) {
                    continue;
                }
                FieldRefParts parts = new FieldRefParts("record", recordDataPath(field.getCode()));
                FieldAccumulator acc = fields.computeIfAbsent(
                        modelScopedKey(model.getCode(), parts),
                        ignored -> new FieldAccumulator(parts));
                acc.dataType = chooseDataType(acc.dataType, field.getDataType());
                acc.modelCode = model.getCode();
                acc.modelName = modelLabel;
                acc.masked = resolveMasked(field);
                acc.permission = resolvePermission(field);
                String fieldLabel = hasText(field.getDisplayName()) ? field.getDisplayName() : field.getCode();
                acc.label = modelLabel + " / " + fieldLabel;
            }
        }
    }

    private String modelScopedKey(String modelCode, FieldRefParts parts) {
        return String.valueOf(modelCode) + "\u0000" + parts.key();
    }

    private String recordDataPath(String fieldCode) {
        return fieldCode.startsWith("data.") ? fieldCode : "data." + fieldCode;
    }

    private DecisionFactEntityDTO buildModelEntity(Model model) {
        DecisionFactEntityDTO entity = new DecisionFactEntityDTO();
        entity.setScope("record");
        entity.setEntityCode(model.getCode());
        entity.setModelCode(model.getCode());
        entity.setLabel(hasText(model.getDisplayName()) ? model.getDisplayName() : model.getCode());
        entity.setSourceType(hasText(model.getSourceType()) ? model.getSourceType() : "physical");
        entity.setSourceRef(model.getSourceRef());

        FieldPermissionSet fieldPermissions = resolveFieldPermissions(model.getCode());
        List<MetaFieldDTO> modelFields = modelFieldBindingService.getModelFields(model.getPid());
        Set<String> existingFieldCodes = new LinkedHashSet<>();
        for (MetaFieldDTO field : modelFields) {
            if (field == null || !Boolean.TRUE.equals(field.getVisible()) || !hasText(field.getCode())) {
                continue;
            }
            if (!canViewField(fieldPermissions, field)) {
                continue;
            }
            existingFieldCodes.add(field.getCode());
            entity.getFacts().add(buildFieldFact(model, field, fieldPermissions));
        }
        appendDeclaredVirtualFieldFacts(model, entity, existingFieldCodes);
        entity.getFacts().sort(Comparator.comparing(DecisionFactDTO::getFactKey));
        return entity;
    }

    private DecisionFactDTO buildFieldFact(Model model, MetaFieldDTO field, FieldPermissionSet fieldPermissions) {
        String dataType = normalizeDataType(field.getDataType());
        DecisionFactDTO fact = new DecisionFactDTO();
        fact.setScope("record");
        fact.setPath(recordDataPath(field.getCode()));
        fact.setFactKey("record." + fact.getPath());
        fact.setModelCode(model.getCode());
        fact.setSourceType(hasText(model.getSourceType()) ? model.getSourceType() : "physical");
        fact.setLabel(field.getDisplayName());
        fact.setDataType(dataType);
        fact.setOperators(operatorsFor(dataType));
        fact.setDictCode(field.getDictCode());
        fact.setAllowedValues(loadDictOptions(field.getDictCode()));
        fact.setReference(field.getRefTarget());
        fact.setRequired(field.getRequired());
        fact.setVisible(field.getVisible());
        fact.setEditable(canEditField(fieldPermissions, field));
        fact.setMasked(resolveMasked(field));
        fact.setPermission(resolvePermission(field));
        return fact;
    }

    private void appendDeclaredVirtualFieldFacts(
            Model model,
            DecisionFactEntityDTO entity,
            Set<String> existingFieldCodes) {
        if (model == null || entity == null || !isVirtualSourceModel(model)) {
            return;
        }
        List<FieldDefinition> declaredFields = metaModelService.getModelFields(model.getCode());
        if (declaredFields == null || declaredFields.isEmpty()) {
            return;
        }
        for (FieldDefinition field : declaredFields) {
            if (field == null || !hasText(field.getCode()) || !existingFieldCodes.add(field.getCode())) {
                continue;
            }
            entity.getFacts().add(buildDeclaredFieldFact(model, field));
        }
    }

    private boolean isVirtualSourceModel(Model model) {
        return model != null
                && hasText(model.getSourceType())
                && !"physical".equalsIgnoreCase(model.getSourceType());
    }

    private DecisionFactDTO buildDeclaredFieldFact(Model model, FieldDefinition field) {
        String dataType = normalizeDataType(field.getDataType());
        DecisionFactDTO fact = new DecisionFactDTO();
        fact.setScope("record");
        fact.setPath(recordDataPath(field.getCode()));
        fact.setFactKey("record." + fact.getPath());
        fact.setModelCode(model.getCode());
        fact.setSourceType(hasText(model.getSourceType()) ? model.getSourceType() : "physical");
        fact.setLabel(firstText(field.getDisplayName(), field.getName(), field.getCode()));
        fact.setDataType(dataType);
        fact.setOperators(operatorsFor(dataType));
        fact.setRequired(field.getRequired());
        fact.setVisible(true);
        fact.setEditable(false);
        fact.setMasked(firstBoolean(
                List.of("masked", "mask", "masking", "sensitive", "pii"),
                field.getExtraProps()));
        fact.setPermission(firstString(
                List.of("permission", "permissionCode", "readPermission", "viewPermission"),
                field.getExtraProps()));
        return fact;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private FieldPermissionSet resolveFieldPermissions(String modelCode) {
        Long memberId = currentMemberIdForFieldPermissions();
        if (memberId == null || !hasText(modelCode)) {
            return null;
        }
        try {
            return fieldPermissionService.getFieldPermissions(memberId, modelCode);
        } catch (Exception ex) {
            throw new ValidationException(ResponseCode.FORBIDDEN,
                    "Decision fact catalog field permissions unavailable for model: " + modelCode);
        }
    }

    private Long currentMemberIdForFieldPermissions() {
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId != null) {
            return memberId;
        }
        return MetaContext.exists() ? MetaContext.get().getUserId() : null;
    }

    private boolean canViewField(FieldPermissionSet permissions, MetaFieldDTO field) {
        if (permissions == null) {
            return true;
        }
        if (containsField(permissions.hiddenFields(), field)) {
            return false;
        }
        if (isEmpty(permissions.viewableFields()) && isEmpty(permissions.hiddenFields())) {
            return true;
        }
        return containsField(permissions.viewableFields(), field);
    }

    private boolean canEditField(FieldPermissionSet permissions, MetaFieldDTO field) {
        boolean fieldEditable = field.getEditable() == null || Boolean.TRUE.equals(field.getEditable());
        if (permissions == null) {
            return fieldEditable;
        }
        if (containsField(permissions.hiddenFields(), field)) {
            return false;
        }
        if (isEmpty(permissions.viewableFields()) && isEmpty(permissions.editableFields())
                && isEmpty(permissions.hiddenFields())) {
            return fieldEditable;
        }
        return fieldEditable && containsField(permissions.editableFields(), field);
    }

    private boolean containsField(Set<String> fields, MetaFieldDTO field) {
        if (fields == null || fields.isEmpty() || field == null || !hasText(field.getCode())) {
            return false;
        }
        String code = field.getCode();
        String path = recordDataPath(code);
        String factKey = "record." + path;
        return fields.contains(code) || fields.contains(path) || fields.contains(factKey);
    }

    private boolean isEmpty(Set<String> values) {
        return values == null || values.isEmpty();
    }

    private Boolean resolveMasked(MetaFieldDTO field) {
        return firstBoolean(
                List.of("masked", "mask", "masking", "sensitive", "pii"),
                field.getRuleSchema(),
                field.getFeature(),
                field.getUiSchema(),
                field.getExtension());
    }

    private String resolvePermission(MetaFieldDTO field) {
        return firstString(
                List.of("permission", "permissionCode", "readPermission", "viewPermission"),
                field.getRuleSchema(),
                field.getFeature(),
                field.getUiSchema(),
                field.getExtension());
    }

    @SafeVarargs
    private final Boolean firstBoolean(List<String> keys, Map<String, Object>... maps) {
        for (Map<String, Object> map : maps) {
            if (map == null || map.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                Object value = map.get(key);
                if (value instanceof Boolean booleanValue) {
                    return booleanValue;
                }
                if (value instanceof String stringValue && hasText(stringValue)) {
                    if ("true".equalsIgnoreCase(stringValue) || "yes".equalsIgnoreCase(stringValue)) {
                        return true;
                    }
                    if ("false".equalsIgnoreCase(stringValue) || "no".equalsIgnoreCase(stringValue)) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    @SafeVarargs
    private final String firstString(List<String> keys, Map<String, Object>... maps) {
        for (Map<String, Object> map : maps) {
            if (map == null || map.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                Object value = map.get(key);
                if (value instanceof String stringValue && hasText(stringValue)) {
                    return stringValue;
                }
            }
        }
        return null;
    }

    private List<DecisionFactOptionDTO> loadDictOptions(String dictCode) {
        if (!hasText(dictCode)) {
            return List.of();
        }
        Dict dict = dictMapper.findCurrentByCode(dictCode);
        if (dict == null || dict.getId() == null || !"published".equalsIgnoreCase(String.valueOf(dict.getStatus()))) {
            return List.of();
        }
        List<DecisionFactOptionDTO> options = new ArrayList<>();
        for (DictItem item : dictItemMapper.findByDictId(dict.getId())) {
            DecisionFactOptionDTO option = new DecisionFactOptionDTO();
            option.setValue(item.getValue());
            option.setLabel(item.getLabel());
            option.setParentValue(item.getParentValue());
            option.setDisabled(!"enabled".equalsIgnoreCase(String.valueOf(item.getStatus())));
            options.add(option);
        }
        return options;
    }

    private List<DecisionFactEntityDTO> sharedContextEntities() {
        List<DecisionFactEntityDTO> entities = new ArrayList<>();
        entities.add(sharedEntity("actor", "Actor", List.of(
                sharedFact("actor", "userId", "Current User", "user"),
                sharedFact("actor", "roleCodes", "Current Roles", "collection"),
                sharedFact("actor", "departmentId", "Department", "department"))));
        entities.add(sharedEntity("event", "Event", List.of(
                sharedFact("event", "type", "Event Type", "string"),
                sharedFact("event", "source", "Event Source", "string"),
                sharedFact("event", "occurredAt", "Occurred At", "datetime"))));
        entities.add(sharedEntity("time", "Time", List.of(
                sharedFact("time", "now", "Current Time", "datetime"),
                sharedFact("time", "businessHours", "Business Hours", "boolean"),
                sharedFact("time", "weekday", "Weekday", "integer"))));
        entities.add(sharedEntity("tenant", "Tenant", List.of(
                sharedFact("tenant", "id", "Tenant ID", "string"),
                sharedFact("tenant", "timezone", "Timezone", "string"),
                sharedFact("tenant", "locale", "Locale", "string"))));
        return entities;
    }

    private DecisionFactEntityDTO sharedEntity(String scope, String label, List<DecisionFactDTO> facts) {
        DecisionFactEntityDTO entity = new DecisionFactEntityDTO();
        entity.setScope(scope);
        entity.setEntityCode(scope);
        entity.setLabel(label);
        entity.setSourceType("runtime");
        entity.setFacts(new ArrayList<>(facts));
        return entity;
    }

    private DecisionFactDTO sharedFact(String scope, String path, String label, String dataType) {
        String normalizedDataType = normalizeDataType(dataType);
        DecisionFactDTO fact = new DecisionFactDTO();
        fact.setScope(scope);
        fact.setPath(path);
        fact.setFactKey(scope + "." + path);
        fact.setLabel(label);
        fact.setDataType(normalizedDataType);
        fact.setSourceType("runtime");
        fact.setOperators(operatorsFor(normalizedDataType));
        fact.setVisible(true);
        fact.setEditable(false);
        return fact;
    }

    private List<String> operatorsFor(String dataType) {
        return switch (normalizeDataType(dataType)) {
            case "integer", "decimal", "duration" -> List.of(
                    "EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IS_EMPTY", "IS_NOT_EMPTY");
            case "date", "time", "datetime" -> List.of(
                    "EQ", "NE", "BEFORE", "AFTER", "BETWEEN", "IS_EMPTY", "IS_NOT_EMPTY");
            case "boolean" -> List.of("EQ", "NE", "IS_EMPTY", "IS_NOT_EMPTY");
            case "enum", "dict", "reference", "user", "role", "group", "department" -> List.of(
                    "EQ", "NE", "IN", "NOT_IN", "IS_EMPTY", "IS_NOT_EMPTY");
            default -> List.of(
                    "EQ", "NE", "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH",
                    "IN", "NOT_IN", "IS_EMPTY", "IS_NOT_EMPTY");
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> parseRefs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                refs.add(item.asText());
            }
        }
        return refs;
    }

    private void collectPathDataTypes(JsonNode node, Map<String, String> dataTypes) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if ("path".equals(node.path("type").asText())) {
                String scope = node.path("scope").asText("");
                String path = node.path("path").asText("");
                if (!scope.isBlank() && !path.isBlank()) {
                    dataTypes.put(scope + "." + path, normalizeDataType(node.path("dataType").asText(null)));
                }
            }
            node.properties().forEach(entry -> collectPathDataTypes(entry.getValue(), dataTypes));
        } else if (node.isArray()) {
            node.forEach(child -> collectPathDataTypes(child, dataTypes));
        }
    }

    private String chooseDataType(String existing, String candidate) {
        if (existing != null && !"object".equals(existing)) {
            return existing;
        }
        return normalizeDataType(candidate);
    }

    private String normalizeDataType(String value) {
        if (value == null || value.isBlank()) {
            return "object";
        }
        String normalized = value.trim().toLowerCase();
        return SUPPORTED_DATA_TYPES.contains(normalized) ? normalized : "object";
    }

    private record FieldRefParts(String entityCode, String path) {
        static FieldRefParts parse(String fieldRef) {
            int dot = fieldRef.indexOf('.');
            if (dot <= 0 || dot == fieldRef.length() - 1) {
                return new FieldRefParts("record", fieldRef);
            }
            return new FieldRefParts(fieldRef.substring(0, dot), fieldRef.substring(dot + 1));
        }

        String key() {
            return entityCode + "\u0000" + path;
        }

        String label() {
            int dot = path.lastIndexOf('.');
            return dot >= 0 && dot < path.length() - 1 ? path.substring(dot + 1) : path;
        }
    }

    private static final class FieldAccumulator {
        private final FieldRefParts parts;
        private final Set<String> decisionCodes = new LinkedHashSet<>();
        private int refs;
        private String dataType = "object";
        private String label;
        private String modelCode;
        private String modelName;
        private Boolean masked = false;
        private String permission;

        private FieldAccumulator(FieldRefParts parts) {
            this.parts = parts;
        }

        private DecisionModelFieldDTO toDTO() {
            DecisionModelFieldDTO dto = new DecisionModelFieldDTO();
            dto.setModelCode(modelCode);
            dto.setModelName(modelName);
            dto.setEntityCode(parts.entityCode());
            dto.setPath(parts.path());
            dto.setLabel(label != null ? label : parts.label());
            dto.setDataType(dataType);
            dto.setRefs(refs);
            dto.setMasked(masked);
            dto.setPermission(permission);
            dto.setDecisionCodes(List.copyOf(decisionCodes));
            return dto;
        }
    }
}
