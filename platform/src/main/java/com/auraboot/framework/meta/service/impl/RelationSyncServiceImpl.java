package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.RelationDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.RelationSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of RelationSyncService for synchronizing bidirectional relations
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationSyncServiceImpl implements RelationSyncService {

    private final MetaModelService metaModelService;
    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper bindingMapper;
    private final DynamicDataMapper dynamicDataMapper;

    @Override
    @Transactional
    public void syncInverseSide(String modelCode, String recordId, String fieldCode,
                                List<String> oldTargetIds, List<String> newTargetIds) {
        log.debug("Syncing inverse side for model={}, record={}, field={}", modelCode, recordId, fieldCode);

        // 1. Get field definition and bidirectional config
        FieldDefinition fieldDef = metaModelService.getFieldDefinition(modelCode, fieldCode);
        if (fieldDef == null) {
            log.warn("Field definition not found: model={}, field={}", modelCode, fieldCode);
            return;
        }

        FieldRefTargetBean.BidirectionalConfig bidirectional = getBidirectionalConfig(modelCode, fieldCode);
        if (bidirectional == null) {
            log.debug("No bidirectional config for field: model={}, field={}", modelCode, fieldCode);
            return;
        }

        // Only sync from owning side
        if (!Boolean.TRUE.equals(bidirectional.getIsOwningSide())) {
            log.debug("Skipping sync for non-owning side: model={}, field={}", modelCode, fieldCode);
            return;
        }

        String relationType = bidirectional.getRelationType();
        String targetModelCode = getTargetModelCode(modelCode, fieldCode);
        String inverseFieldCode = bidirectional.getInverseFieldCode();

        if (!StringUtils.hasText(targetModelCode) || !StringUtils.hasText(inverseFieldCode)) {
            log.warn("Missing target model or inverse field: targetModel={}, inverseField={}",
                    targetModelCode, inverseFieldCode);
            return;
        }

        // Calculate diff
        Set<String> oldSet = oldTargetIds != null ? new HashSet<>(oldTargetIds) : Collections.emptySet();
        Set<String> newSet = newTargetIds != null ? new HashSet<>(newTargetIds) : Collections.emptySet();

        Set<String> toAdd = new HashSet<>(newSet);
        toAdd.removeAll(oldSet);

        Set<String> toRemove = new HashSet<>(oldSet);
        toRemove.removeAll(newSet);

        log.debug("Relation sync diff: toAdd={}, toRemove={}", toAdd.size(), toRemove.size());

        // Sync based on relation type
        switch (relationType) {
            case "one_to_one" -> syncOneToOne(modelCode, recordId, targetModelCode, inverseFieldCode, toAdd, toRemove);
            case "one_to_many" -> syncOneToMany(modelCode, recordId, targetModelCode, inverseFieldCode, toAdd, toRemove);
            case "many_to_many" -> syncManyToMany(modelCode, recordId, targetModelCode, bidirectional, toAdd, toRemove);
            case "many_to_one" -> {
                // No action on inverse side for MANY_TO_ONE - this side holds the FK
                log.debug("MANY_TO_ONE: No inverse sync needed");
            }
            default -> log.warn("Unknown relation type: {}", relationType);
        }
    }

    @Override
    public Map<String, InverseFieldInfo> getInverseFields(String modelCode) {
        log.debug("Getting inverse fields for model: {}", modelCode);

        Map<String, InverseFieldInfo> result = new HashMap<>();

        // Get model
        Model model = metaModelMapper.findCurrentByCode(modelCode);
        if (model == null) {
            log.warn("Model not found: {}", modelCode);
            return result;
        }

        // Get all field bindings for this model
        List<ModelFieldBinding> bindings = bindingMapper.findByModelId(model.getId());
        if (bindings.isEmpty()) {
            return result;
        }

        // Get all field IDs
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .collect(Collectors.toList());

        // Batch load fields
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);

        // Check each field for bidirectional config
        for (Field field : fields) {
            FieldRefTargetBean refTarget = field.getRefTarget();
            if (refTarget == null || refTarget.getBidirectional() == null) {
                continue;
            }

            FieldRefTargetBean.BidirectionalConfig bidirectional = refTarget.getBidirectional();
            if (!StringUtils.hasText(bidirectional.getInverseFieldCode())) {
                continue;
            }

            String targetModelCode = refTarget.getTargetEntity();
            if (!StringUtils.hasText(targetModelCode)) {
                continue;
            }

            result.put(field.getCode(), new InverseFieldInfo(
                    targetModelCode,
                    bidirectional.getInverseFieldCode(),
                    bidirectional.getRelationType(),
                    Boolean.TRUE.equals(bidirectional.getIsOwningSide())
            ));
        }

        log.debug("Found {} inverse fields for model {}", result.size(), modelCode);
        return result;
    }

    @Override
    public List<String> validateBidirectionalConfig(String modelCode, String fieldCode) {
        log.debug("Validating bidirectional config: model={}, field={}", modelCode, fieldCode);

        List<String> errors = new ArrayList<>();

        // 1. Get source field
        FieldRefTargetBean.BidirectionalConfig sourceConfig = getBidirectionalConfig(modelCode, fieldCode);
        if (sourceConfig == null) {
            errors.add("Field does not have bidirectional configuration");
            return errors;
        }

        // 2. Validate required fields
        if (!StringUtils.hasText(sourceConfig.getInverseFieldCode())) {
            errors.add("inverseFieldCode is required for bidirectional relation");
        }

        if (!StringUtils.hasText(sourceConfig.getRelationType())) {
            errors.add("relationType is required for bidirectional relation");
        }

        String targetModelCode = getTargetModelCode(modelCode, fieldCode);
        if (!StringUtils.hasText(targetModelCode)) {
            errors.add("targetEntity is required in refTarget for bidirectional relation");
            return errors;
        }

        // 3. Validate target model exists
        if (!metaModelService.isModelExists(targetModelCode)) {
            errors.add("Target model does not exist: " + targetModelCode);
            return errors;
        }

        // 4. Validate inverse field exists on target model
        String inverseFieldCode = sourceConfig.getInverseFieldCode();
        if (StringUtils.hasText(inverseFieldCode)) {
            if (!metaModelService.isFieldExists(targetModelCode, inverseFieldCode)) {
                errors.add("Inverse field does not exist: " + targetModelCode + "." + inverseFieldCode);
                return errors;
            }

            // 5. Validate inverse field points back to source
            FieldRefTargetBean.BidirectionalConfig inverseConfig = getBidirectionalConfig(targetModelCode, inverseFieldCode);
            if (inverseConfig != null) {
                String inverseTargetModel = getTargetModelCode(targetModelCode, inverseFieldCode);
                if (!modelCode.equals(inverseTargetModel)) {
                    errors.add("Inverse field does not point back to source model. Expected: " + modelCode + ", Got: " + inverseTargetModel);
                }

                // Validate relation type compatibility
                String expectedInverseType = getExpectedInverseRelationType(sourceConfig.getRelationType());
                if (expectedInverseType != null && !expectedInverseType.equals(inverseConfig.getRelationType())) {
                    errors.add("Incompatible relation types. Source: " + sourceConfig.getRelationType() +
                            ", Expected inverse: " + expectedInverseType +
                            ", Actual inverse: " + inverseConfig.getRelationType());
                }

                // Validate owning side configuration
                boolean sourceIsOwning = Boolean.TRUE.equals(sourceConfig.getIsOwningSide());
                boolean inverseIsOwning = Boolean.TRUE.equals(inverseConfig.getIsOwningSide());
                if (sourceIsOwning && inverseIsOwning) {
                    errors.add("Both sides cannot be owning side");
                }
            }
        }

        // 6. Validate MANY_TO_MANY specific configuration
        if ("many_to_many".equals(sourceConfig.getRelationType())) {
            if (Boolean.TRUE.equals(sourceConfig.getIsOwningSide())) {
                if (!StringUtils.hasText(sourceConfig.getJunctionTable())) {
                    errors.add("junctionTable is required for MANY_TO_MANY owning side");
                }
                if (!StringUtils.hasText(sourceConfig.getJunctionSourceColumn())) {
                    errors.add("junctionSourceColumn is required for MANY_TO_MANY owning side");
                }
                if (!StringUtils.hasText(sourceConfig.getJunctionTargetColumn())) {
                    errors.add("junctionTargetColumn is required for MANY_TO_MANY owning side");
                }
            }
        }

        if (errors.isEmpty()) {
            log.debug("Bidirectional config validation passed: model={}, field={}", modelCode, fieldCode);
        } else {
            log.warn("Bidirectional config validation failed: model={}, field={}, errors={}", modelCode, fieldCode, errors);
        }

        return errors;
    }

    // ==================== Private Helper Methods ====================

    /**
     * Sync ONE_TO_ONE relation
     * Clear old and set new on target side
     */
    private void syncOneToOne(String sourceModel, String recordId, String targetModel,
                              String inverseField, Set<String> toAdd, Set<String> toRemove) {
        log.debug("Syncing ONE_TO_ONE: source={}, target={}", sourceModel, targetModel);

        String targetTable = metaModelService.getTableName(targetModel);
        String inverseColumn = getColumnName(targetModel, inverseField);

        // Clear old references
        for (String targetId : toRemove) {
            Map<String, Object> data = new HashMap<>();
            data.put(inverseColumn, null);

            Map<String, Object> conditions = new HashMap<>();
            conditions.put("id", targetId);

            dynamicDataMapper.update(targetTable, data, conditions);
            log.debug("ONE_TO_ONE: Cleared reference on target record {}", targetId);
        }

        // Set new references
        for (String targetId : toAdd) {
            Map<String, Object> data = new HashMap<>();
            data.put(inverseColumn, recordId);

            Map<String, Object> conditions = new HashMap<>();
            conditions.put("id", targetId);

            dynamicDataMapper.update(targetTable, data, conditions);
            log.debug("ONE_TO_ONE: Set reference on target record {} to {}", targetId, recordId);
        }
    }

    /**
     * Sync ONE_TO_MANY relation
     * Update FK on target records
     */
    private void syncOneToMany(String sourceModel, String recordId, String targetModel,
                               String inverseField, Set<String> toAdd, Set<String> toRemove) {
        log.debug("Syncing ONE_TO_MANY: source={}, target={}", sourceModel, targetModel);

        String targetTable = metaModelService.getTableName(targetModel);
        String inverseColumn = getColumnName(targetModel, inverseField);

        // Remove FK from old target records (set to null)
        for (String targetId : toRemove) {
            Map<String, Object> data = new HashMap<>();
            data.put(inverseColumn, null);

            Map<String, Object> conditions = new HashMap<>();
            conditions.put("id", targetId);

            dynamicDataMapper.update(targetTable, data, conditions);
            log.debug("ONE_TO_MANY: Removed FK from target record {}", targetId);
        }

        // Set FK on new target records
        for (String targetId : toAdd) {
            Map<String, Object> data = new HashMap<>();
            data.put(inverseColumn, recordId);

            Map<String, Object> conditions = new HashMap<>();
            conditions.put("id", targetId);

            dynamicDataMapper.update(targetTable, data, conditions);
            log.debug("ONE_TO_MANY: Set FK on target record {} to {}", targetId, recordId);
        }
    }

    /**
     * Sync MANY_TO_MANY relation
     * Insert/delete junction table records
     */
    private void syncManyToMany(String sourceModel, String recordId, String targetModel,
                                FieldRefTargetBean.BidirectionalConfig config,
                                Set<String> toAdd, Set<String> toRemove) {
        log.debug("Syncing MANY_TO_MANY: source={}, target={}", sourceModel, targetModel);

        String junctionTable = config.getJunctionTable();
        String sourceColumn = config.getJunctionSourceColumn();
        String targetColumn = config.getJunctionTargetColumn();

        if (!StringUtils.hasText(junctionTable) || !StringUtils.hasText(sourceColumn) || !StringUtils.hasText(targetColumn)) {
            log.error("Missing junction table configuration for MANY_TO_MANY");
            return;
        }

        // Delete removed relations
        for (String targetId : toRemove) {
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(sourceColumn, recordId);
            conditions.put(targetColumn, targetId);

            dynamicDataMapper.delete(junctionTable, conditions);
            log.debug("MANY_TO_MANY: Deleted junction record source={}, target={}", recordId, targetId);
        }

        // Insert new relations
        for (String targetId : toAdd) {
            Map<String, Object> data = new HashMap<>();
            data.put(sourceColumn, recordId);
            data.put(targetColumn, targetId);

            dynamicDataMapper.insert(junctionTable, data);
            log.debug("MANY_TO_MANY: Inserted junction record source={}, target={}", recordId, targetId);
        }
    }

    /**
     * Get bidirectional config from field
     */
    private FieldRefTargetBean.BidirectionalConfig getBidirectionalConfig(String modelCode, String fieldCode) {
        // Get model
        Model model = metaModelMapper.findCurrentByCode(modelCode);
        if (model == null) {
            return null;
        }

        // Get field bindings
        List<ModelFieldBinding> bindings = bindingMapper.findByModelId(model.getId());

        // Find the specific field
        for (ModelFieldBinding binding : bindings) {
            Field field = metaFieldMapper.selectById(binding.getFieldId());
            if (field != null && fieldCode.equals(field.getCode())) {
                FieldRefTargetBean refTarget = field.getRefTarget();
                if (refTarget != null) {
                    return refTarget.getBidirectional();
                }
                break;
            }
        }

        return null;
    }

    /**
     * Get target model code from field's refTarget
     */
    private String getTargetModelCode(String modelCode, String fieldCode) {
        Model model = metaModelMapper.findCurrentByCode(modelCode);
        if (model == null) {
            return null;
        }

        List<ModelFieldBinding> bindings = bindingMapper.findByModelId(model.getId());

        for (ModelFieldBinding binding : bindings) {
            Field field = metaFieldMapper.selectById(binding.getFieldId());
            if (field != null && fieldCode.equals(field.getCode())) {
                FieldRefTargetBean refTarget = field.getRefTarget();
                if (refTarget != null) {
                    return refTarget.getTargetEntity();
                }
                break;
            }
        }

        return null;
    }

    /**
     * Get column name for a field
     */
    private String getColumnName(String modelCode, String fieldCode) {
        // Try to get column name from MetaModelService
        try {
            return metaModelService.getColumnName(modelCode, fieldCode);
        } catch (Exception e) {
            // Fallback to snake_case conversion
            return toSnakeCase(fieldCode);
        }
    }

    /**
     * Convert camelCase to snake_case
     */
    private String toSnakeCase(String camelCase) {
        if (camelCase == null) {
            return null;
        }
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * Get expected inverse relation type based on source relation type
     */
    private String getExpectedInverseRelationType(String sourceType) {
        if (sourceType == null) {
            return null;
        }
        return switch (sourceType) {
            case "one_to_one" -> "one_to_one";
            case "one_to_many" -> "many_to_one";
            case "many_to_one" -> "one_to_many";
            case "many_to_many" -> "many_to_many";
            default -> null;
        };
    }
}
