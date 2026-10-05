package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;

/** Projects persisted models, field relations and capabilities into runtime definitions and DTOs. */
@Slf4j
@RequiredArgsConstructor
final class ModelDefinitionProjection extends BaseMetaService {
    private final MetaModelMapper metaModelMapper;

    private final MetaFieldMapper metaFieldMapper;

    private final MetaModelFieldBindingMapper fieldBindingMapper;

    private final ObjectMapper objectMapper;

    private final ConvertExtensionToMapOperation convertExtensionToMapOperation;

    @FunctionalInterface
    interface ConvertExtensionToMapOperation { Map<String, Object> execute(ExtensionBean bean); }

    private Map<String, Object> convertExtensionToMap(ExtensionBean bean) { return convertExtensionToMapOperation.execute(bean); }

    ModelDefinition convertToModelDefinition(Model model){
        Map<String, Object> flatExt = flattenExtension(model);
        String primaryKey = flatExt != null ? (String) flatExt.get("primaryKey") : null;
        return ModelDefinition.builder()
                .id(model.getId())
                .code(model.getCode())
                .name(model.getCode()) // 使用code作为name
                .displayName(model.getDisplayName())
                .description(model.getDescription())
                .tableName(resolveTableName(model))
                .modelType(model.getModelType())
                .modelCategory(model.getEffectiveModelCategory())
                .sourceType(model.getSourceType() != null ? model.getSourceType() : "physical")
                .sourceRef(model.getSourceRef())
                .capabilities(parseCapabilities(model.getCapabilities()))
                .primaryKey(primaryKey)
                .version(model.getVersion())
                .status(model.getStatus() != null ? model.getStatus() : null)
                .createdAt(DateUtil.toUtcLocalDateTime(model.getCreatedAt()))
                .updatedAt(DateUtil.toUtcLocalDateTime(model.getUpdatedAt()))
                .softDelete(resolveSoftDelete(model))
                .immutable(resolveImmutable(model))
                .commandOnlyCreate(resolveCommandOnlyCreate(model))
                .rules(loadCrossFieldRules(model))
                .extension(flatExt)
                .build();
    }

    ModelCapabilities parseCapabilities(String json){
        if (json == null || json.isBlank()) {
            return ModelCapabilities.empty();
        }
        try {
            return objectMapper.readValue(json, ModelCapabilities.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MetaServiceException(
                "Failed to parse capabilities JSON for model; data corruption: " + e.getMessage(), e);
        }
    }

    ModelCapabilities normalizeCapabilities(ModelDefinition def){
        ModelCapabilities raw;
        if (def.getCapabilities() != null) {
            raw = def.getCapabilities();
        } else {
            String st = def.getSourceType();
            raw = (st == null || "physical".equals(st))
                ? ModelCapabilities.fullPhysical()
                : ModelCapabilities.empty();
        }

        java.util.List<String> sortable = new java.util.ArrayList<>();
        java.util.List<String> filterable = new java.util.ArrayList<>();
        if (def.getFields() != null) {
            for (FieldDefinition f : def.getFields()) {
                if (Boolean.TRUE.equals(f.getSortable())) sortable.add(f.getCode());
                if (Boolean.TRUE.equals(f.getFilterable())) filterable.add(f.getCode());
            }
        }

        return raw.toBuilder()
            .sortableFields(sortable)       // override any caller-supplied value
            .filterableFields(filterable)   // override any caller-supplied value
            .build();
    }

    List<CrossFieldRule> loadCrossFieldRules(Model model){
        if (model.getExtension() == null) return null;
        // Try flat: {"rules": [...]} then nested: {"extension": {"rules": [...]}}
        Object rulesObj = model.getExtension().get("rules");
        if (rulesObj == null) {
            Object nested = model.getExtension().get("extension");
            if (nested instanceof Map<?, ?> nestedMap) {
                rulesObj = nestedMap.get("rules");
            }
        }
        if (rulesObj == null) return null;
        if (rulesObj instanceof List<?> rawList) {
            try {
                return objectMapper.convertValue(rawList,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CrossFieldRule.class));
            } catch (Exception e) {
                // §P2 best-effort: malformed cross-field-rule JSON should not block
                // model load. Caller treats null as "no rules"; warn log surfaces
                // the bad config for the model owner to fix.
                log.warn("Failed to parse cross-field rules for model {}: {}", logSafe(model.getCode()), logSafe(e.getMessage()), e);
                return null;
            }
        }
        return null;
    }

    String resolveTableName(Model model){
        if (model.getTableName() != null && !model.getTableName().trim().isEmpty()) {
            return model.getTableName().trim();
        }
        return generateTableName(model.getCode());
    }

    Map<String, Object> flattenExtension(Model model){
        ExtensionBean ext = model.getExtension();
        if (ext == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        // Nested: ExtensionBean.extension field
        Map<String, Object> nested = ext.getExtension();
        if (nested != null) {
            result.putAll(nested);
        }
        // Flat dynamic props (from @JsonAnySetter) take precedence over nested.
        Map<String, Object> dynamic = ext.getDynamicProperties();
        if (dynamic != null) {
            for (Map.Entry<String, Object> e : dynamic.entrySet()) {
                if (!"extension".equals(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    boolean resolveSoftDelete(Model model){
        if (model.getExtension() != null) {
            Object sd = model.getExtension().get("softDelete");
            return Boolean.TRUE.equals(sd) || "true".equals(String.valueOf(sd));
        }
        return false;
    }

    boolean resolveImmutable(Model model){
        if (model.getExtension() != null) {
            Object immutable = model.getExtension().get("immutable");
            return Boolean.TRUE.equals(immutable) || "true".equals(String.valueOf(immutable));
        }
        return false;
    }

    boolean resolveCommandOnlyCreate(Model model){
        if (model.getExtension() != null) {
            Object value = model.getExtension().get("commandOnlyCreate");
            return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
        }
        return false;
    }

    List<RelationDefinition> loadModelRelations(Long modelId){
        Model model = metaModelMapper.selectById(modelId);
        if (model == null) {
            return Collections.emptyList();
        }
        List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(modelId);
        if (bindings == null || bindings.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .collect(Collectors.toList());
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }
        String sourceModel = model.getCode();
        String sourceTable = generateTableName(sourceModel);
        return fields.stream()
                .map(field -> buildRelationDefinition(field, sourceModel, sourceTable))
                .filter(relation -> relation != null)
                .collect(Collectors.toList());
    }

    RelationDefinition buildRelationDefinition(Field field, String sourceModel, String sourceTable){
        FieldRefTargetBean refTarget = field.getRefTarget();
        if (refTarget == null || !StringUtils.hasText(refTarget.getTargetEntity())) {
            return null;
        }
        FieldRefTargetBean.BidirectionalConfig bidi = refTarget.getBidirectional();
        if (bidi == null) {
            return null;
        }
        RelationDefinition.RelationType relationType = parseRelationType(bidi.getRelationType());
        if (relationType == null) {
            return null;
        }
        String targetModel = refTarget.getTargetEntity();
        RelationDefinition.RelationDefinitionBuilder builder = RelationDefinition.builder()
                .name(field.getCode())
                .sourceModel(sourceModel)
                .targetModel(targetModel)
                .sourceTable(sourceTable)
                .targetTable(StringUtils.hasText(refTarget.getTargetTable())
                        ? refTarget.getTargetTable() : generateTableName(targetModel))
                .relationType(relationType)
                .lazy(bidi.getLazyFetch() == null || Boolean.TRUE.equals(bidi.getLazyFetch()));
        if (relationType == RelationDefinition.RelationType.MANY_TO_MANY) {
            builder.joinTable(bidi.getJunctionTable())
                    .sourceField(bidi.getJunctionSourceColumn())
                    .targetField(bidi.getJunctionTargetColumn());
        } else {
            builder.sourceField(field.getCode())
                    .targetField(StringUtils.hasText(refTarget.getTargetField())
                            ? refTarget.getTargetField() : "pid");
        }
        return builder.build();
    }

    RelationDefinition.RelationType parseRelationType(String raw){
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return RelationDefinition.RelationType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown relation type '{}' in field bidirectional config; relation skipped", raw);
            return null;
        }
    }

    String generateTableName(String modelCode){
        return SystemFieldConstants.generateTableName(modelCode);
    }

    MetaModelDTO convertToMetaModelDTO(Model model){
        Integer fieldCount = model.getId() != null
                ? fieldBindingMapper.countUserFieldsByModelId(model.getId())
                : 0;
        return convertToMetaModelDTO(model, fieldCount);
    }

    MetaModelDTO convertToMetaModelDTO(Model model, Integer fieldCount){
        return MetaModelDTO.builder()
                .id(model.getId())
                .pid(model.getPid())
                .tenantId(model.getTenantId())

                .code(model.getCode())
                .displayName(model.getDisplayName())
                .description(model.getDescription())
                .modelType(model.getModelType())
                .modelCategory(model.getEffectiveModelCategory())
                .tableName(resolveTableName(model))
                .sourceType(model.getSourceType())
                .sourceRef(model.getSourceRef())
                .extension(convertExtensionToMap(model.getExtension()))
                .fieldCount(fieldCount != null ? fieldCount : 0)
                .version(model.getVersion())
                .isCurrent(model.getIsCurrent())
                .status(model.getStatus() != null ? model.getStatus() : null)
                .createdAt(DateUtil.toUtcLocalDateTime(model.getCreatedAt()))
                .updatedAt(DateUtil.toUtcLocalDateTime(model.getUpdatedAt()))
                .build();
    }

    Map<Long, Integer> loadUserFieldCountsByModelId(List<Model> models){
        List<Long> modelIds = models.stream()
                .map(Model::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (modelIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return fieldBindingMapper.countUserFieldsByModelIds(modelIds).stream()
                .filter(row -> row.getModelId() != null)
                .collect(Collectors.toMap(
                        MetaModelFieldBindingMapper.ModelFieldCount::getModelId,
                        row -> row.getFieldCount() != null ? row.getFieldCount() : 0,
                        (left, right) -> right
                ));
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
