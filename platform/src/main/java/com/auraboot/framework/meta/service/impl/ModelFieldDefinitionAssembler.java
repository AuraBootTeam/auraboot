package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;

/** Owns field definition assembly and physical type mapping. */
@Slf4j
@RequiredArgsConstructor
final class ModelFieldDefinitionAssembler {
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final ObjectMapper objectMapper;

    List<FieldDefinition> loadFieldDefinitions(Long modelId) {
        try {
            // 1. 查询模型字段绑定关系
            List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(modelId);
            log.info("Found {} field bindings for model ID: {}", bindings.size(), modelId);

            List<FieldDefinition> fieldDefinitions = new ArrayList<>();
            Set<String> existingFieldCodes = new HashSet<>();

            if (!bindings.isEmpty()) {
                // 2. 提取所有fieldId并批量查询（避免N+1）
                List<Long> fieldIds = bindings.stream()
                    .map(ModelFieldBinding::getFieldId)
                    .toList();
                log.debug("Field IDs to load: {}", fieldIds);

                List<Field> fieldEntities = metaFieldMapper.findByIds(fieldIds);
                log.info("Loaded {} field entities from database for model ID: {}", fieldEntities.size(), modelId);

                if (!fieldEntities.isEmpty()) {
                    // 3. 构建fieldId到fieldOrder的映射
                    Map<Long, ModelFieldBinding> bindingMap = bindings.stream()
                        .collect(Collectors.toMap(
                            ModelFieldBinding::getFieldId,
                            binding -> binding
                        ));

                    // 4. 组装FieldDefinition列表
                    for (Field fieldEntity : fieldEntities) {
                        ModelFieldBinding binding = bindingMap.get(fieldEntity.getId());
                        Integer fieldOrder = binding != null ? binding.getFieldOrder() : null;
                        FieldDefinition fd = convertToFieldDefinition(fieldEntity, fieldOrder);
                        // GAP-265: required-ness is a per-binding concept (one field can be
                        // required in model_A but optional in model_B). Binding is the authoritative
                        // source after GAP-259 stopped propagating constraints.required to the global
                        // FieldFeatureBean. Override field-level required with binding value (both
                        // directions), so all downstream readers of FieldDefinition.isRequired()
                        // (DDL emission, validation, Excel template, BPM form metadata, page meta,
                        // plugin generator) automatically honor the per-binding required flag.
                        if (binding != null) {
                            fd.setRequired(Boolean.TRUE.equals(binding.getRequired()));
                        }
                        if (binding != null && Boolean.TRUE.equals(binding.getSearchable())) {
                            fd.setSearchable(true);
                        }
                        fieldDefinitions.add(fd);
                        existingFieldCodes.add(fd.getCode());
                    }
                }
            }

            // 5. 自动补充系统字段（如果不存在）
            appendSystemFieldsIfMissing(fieldDefinitions, existingFieldCodes);

            // 6. 按排序顺序排列
            fieldDefinitions.sort((a, b) -> Integer.compare(
                a.getSortOrder() != null ? a.getSortOrder() : 0,
                b.getSortOrder() != null ? b.getSortOrder() : 0
            ));

            log.info("Loaded {} field definitions (including system fields) for model ID: {}",
                     fieldDefinitions.size(), modelId);
            return fieldDefinitions;

        } catch (Exception e) {
            log.error("Failed to load field definitions for model ID: {}", modelId, e);
            throw new MetaServiceException("Failed to load field definitions for model ID: " + modelId, e);
        }
    }

    List<FieldDefinition> mergeDeclaredExtensionFields(
            ModelDefinition modelDefinition,
            List<FieldDefinition> boundFields) {
        if (modelDefinition == null
                || modelDefinition.getExtension() == null
                || !(modelDefinition.getExtension().get("fields") instanceof List<?> declared)
                || declared.isEmpty()) {
            return boundFields;
        }
        List<FieldDefinition> merged = new ArrayList<>();
        Set<String> existingCodes = new LinkedHashSet<>();
        if (boundFields != null) {
            for (FieldDefinition field : boundFields) {
                if (field == null || !StringUtils.hasText(field.getCode())) {
                    continue;
                }
                merged.add(field);
                existingCodes.add(field.getCode());
            }
        }
        for (Object raw : declared) {
            FieldDefinition field = raw instanceof FieldDefinition fieldDefinition
                    ? fieldDefinition
                    : objectMapper.convertValue(raw, FieldDefinition.class);
            if (field == null || !StringUtils.hasText(field.getCode()) || !existingCodes.add(field.getCode())) {
                continue;
            }
            merged.add(field);
        }
        return merged;
    }

    void appendSystemFieldsIfMissing(List<FieldDefinition> fields, Set<String> existingCodes) {
        // id - 数据库物理主键（自增），系统自动生成，不要求用户提供
        if (!existingCodes.contains("id")) {
            fields.add(FieldDefinition.builder()
                    .code("id")
                    .name("id")
                    .columnName("id")
                    .dataType("long")
                    .primaryKey(false)  // 业务层不作为主键
                    .required(false)    // 系统自动生成
                    .sortOrder(-1000)
                    .build());
        }

        // pid - 业务主键（UUID），系统自动生成，不要求用户提供
        if (!existingCodes.contains("pid")) {
            fields.add(FieldDefinition.builder()
                    .code("pid")
                    .name("pid")
                    .columnName("pid")
                    .dataType("string")
                    .primaryKey(true)   // 业务主键
                    .required(false)    // 系统自动生成
                    .sortOrder(-999)
                    .build());
        }

        // created_at - 创建时间
        if (!existingCodes.contains("created_at")) {
            fields.add(FieldDefinition.builder()
                    .code("created_at")
                    .name("created_at")
                    .columnName("created_at")
                    .dataType("datetime")
                    .sortOrder(-998)
                    .build());
        }

        // updated_at - 更新时间
        if (!existingCodes.contains("updated_at")) {
            fields.add(FieldDefinition.builder()
                    .code("updated_at")
                    .name("updated_at")
                    .columnName("updated_at")
                    .dataType("datetime")
                    .sortOrder(-997)
                    .build());
        }

        // created_by - 创建人
        if (!existingCodes.contains("created_by")) {
            fields.add(FieldDefinition.builder()
                    .code("created_by")
                    .name("created_by")
                    .columnName("created_by")
                    .dataType("long")
                    .sortOrder(-996)
                    .build());
        }

        // updated_by - 更新人
        if (!existingCodes.contains("updated_by")) {
            fields.add(FieldDefinition.builder()
                    .code("updated_by")
                    .name("updated_by")
                    .columnName("updated_by")
                    .dataType("long")
                    .sortOrder(-995)
                    .build());
        }

        // tenant_id - 租户ID，从上下文自动获取，不要求用户提供
        if (!existingCodes.contains("tenant_id")) {
            fields.add(FieldDefinition.builder()
                    .code("tenant_id")
                    .name("tenant_id")
                    .columnName("tenant_id")
                    .dataType("long")
                    .required(false)    // 系统自动获取
                    .sortOrder(-994)
                    .build());
        }
    }

    Map<String, Object> convertExtensionToMap(ExtensionBean bean) {
        if (bean == null) return null;
        Map<String, Object> result = new HashMap<>();
        if (bean.getExtension() != null) {
            result.putAll(bean.getExtension());
        }
        if (bean.getDynamicProperties() != null) {
            result.putAll(bean.getDynamicProperties());
        }
        return result.isEmpty() ? null : result;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> flattenFieldExtension(ExtensionBean bean) {
        Map<String, Object> result = new HashMap<>();
        if (bean == null) {
            return result;
        }
        if (bean.getExtension() != null) {
            Object nested = bean.getExtension().get("extension");
            if (nested instanceof Map<?, ?> nestedMap) {
                result.putAll((Map<String, Object>) nestedMap);
            }
            for (Map.Entry<String, Object> entry : bean.getExtension().entrySet()) {
                if (!"extension".equals(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<String, Object> dynamic = bean.getDynamicProperties();
        if (dynamic != null) {
            Object nested = dynamic.get("extension");
            if (nested instanceof Map<?, ?> nestedMap) {
                result.putAll((Map<String, Object>) nestedMap);
            }
            for (Map.Entry<String, Object> entry : dynamic.entrySet()) {
                if (!"extension".equals(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    FieldDefinition convertToFieldDefinition(Field field, Integer sortOrder) {
        if (field == null) {
            return null;
        }

        // 从feature中提取字段属性
        FieldFeatureBean feature = field.getFeature();
        Map<String, Object> extensionMap = flattenFieldExtension(field.getExtension());
        Map<String, Object> constraintsMap = extractNestedMap(extensionMap.get("constraints"));

        return FieldDefinition.builder()
                .code(field.getCode())
                .name(field.getCode())
                .displayName((String) extensionMap.get("displayName"))
                .description((String) extensionMap.get("description"))
                .dataType(field.getDataType())
                .columnName(resolveColumnName(field.getCode(), extensionMap))
                .required(feature != null ? Boolean.TRUE.equals(feature.getRequired()) : false)
                .primaryKey(Boolean.TRUE.equals(extensionMap.get("primaryKey")) || Boolean.TRUE.equals(extensionMap.get("isPrimaryKey")))
                .unique(feature != null ? Boolean.TRUE.equals(feature.getUnique()) : false)
                .displayField(Boolean.TRUE.equals(extensionMap.get("displayField")))
                .defaultValue(feature != null ? feature.getDefaultValue() : null)
                .maxLength(readInteger(extensionMap, constraintsMap, "maxLength"))
                .minLength(readInteger(extensionMap, constraintsMap, "minLength"))
                .maxValue(readValue(extensionMap, constraintsMap, "maxValue", "max"))
                .minValue(readValue(extensionMap, constraintsMap, "minValue", "min"))
                .format((String) extensionMap.get("format"))
                .precision(readInteger(extensionMap, constraintsMap, "precision"))
                .scale(readInteger(extensionMap, constraintsMap, "scale"))
                .sortOrder(sortOrder)
                .dataTypeMapping(createDataTypeMapping(field.getDataType()))
                .validationRules(Collections.emptyList()) // TODO: 实现验证规则转换
                .virtualType(feature != null ? feature.getVirtualType() : null)
                .computeExpression(feature != null ? feature.getComputeExpression() : null)
                .computeDependencies(feature != null ? feature.getComputeDependencies() : null)
                .jsonbColumn((String) extensionMap.get("jsonbColumn"))
                .jsonbPath((String) extensionMap.get("jsonbPath"))
                .refTarget(convertRefTargetBeanToDto(field.getRefTarget()))
                .immutable(Boolean.TRUE.equals(extensionMap.get("immutable")))
                .immutableWhen(readImmutableWhen(extensionMap.get("immutableWhen")))
                .allowedWriterCommands(readAllowedWriterCommands(extensionMap))
                .extraProps(extensionMap)
                .build();
    }

    FieldDefinition.ImmutableWhen readImmutableWhen(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object field = map.get("field");
        Object states = map.get("in");
        List<String> in = states instanceof Collection<?> collection
                ? collection.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList()
                : null;
        return FieldDefinition.ImmutableWhen.builder()
                .field(field == null ? null : String.valueOf(field))
                .in(in)
                .build();
    }

    List<String> readAllowedWriterCommands(Map<String, Object> extensionMap) {
        if (!extensionMap.containsKey("allowedWriterCommands")) {
            return null;
        }
        Object raw = extensionMap.get("allowedWriterCommands");
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        if (collection.stream().anyMatch(value -> !(value instanceof String code) || code.isBlank())) {
            return List.of();
        }
        return collection.stream().map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> extractNestedMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    Integer readInteger(Map<String, Object> primary, Map<String, Object> constraints, String key) {
        Object raw = readValue(primary, constraints, key, key);
        if (raw instanceof Integer integer) {
            return integer;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    Object readValue(Map<String, Object> primary, Map<String, Object> constraints, String primaryKey, String fallbackKey) {
        if (primary.containsKey(primaryKey)) {
            return primary.get(primaryKey);
        }
        return constraints.get(fallbackKey);
    }

    FieldDefinition.RefTarget convertRefTargetBeanToDto(FieldRefTargetBean bean) {
        if (bean == null || bean.getTargetEntity() == null) return null;
        return FieldDefinition.RefTarget.builder()
                .targetEntity(bean.getTargetEntity())
                .targetTable(bean.getTargetTable())
                .valueField(bean.getValueField())
                .targetField(bean.getTargetField())
                .displayField(bean.getDisplayField())
                .importMatchFields(bean.getImportMatchFields())
                .build();
    }

    String generateColumnName(String code) {
        // 简单的列名生成规则，将驼峰转换为下划线
        return code.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    String resolveColumnName(String fieldCode, Map<String, Object> extension) {
        Object configured = extension.get("columnName");
        if (configured instanceof String columnName && !columnName.isBlank()) {
            return columnName;
        }
        return generateColumnName(fieldCode);
    }

    DataTypeMapping createDataTypeMapping(String dataType) {
        if (dataType == null) {
            return null;
        }

        // 简单的数据类型映射
        return DataTypeMapping.builder()
                .javaType(mapToJavaType(dataType))
                .jdbcType(mapToJdbcType(dataType))
                .dbType(mapToPhysicalType(dataType))
                .nullable(true)
                .build();
    }

    String mapToJavaType(String logicalType) {
        switch (logicalType.toLowerCase(java.util.Locale.ROOT)) {
            case "string":
                return "String";
            case "integer":
                return "Integer";
            case "long":
                return "Long";
            case "decimal":
                return "BigDecimal";
            case "date":
                return "LocalDate";
            case "datetime":
                return "LocalDateTime";
            case "boolean":
                return "Boolean";
            case "text":
                return "String";
            default:
                return "String";
        }
    }

    String mapToPhysicalType(String logicalType) {
        switch (logicalType.toLowerCase(java.util.Locale.ROOT)) {
            case "string":
                return "varchar";
            case "integer":
                return "integer";
            case "long":
                return "bigint";
            case "decimal":
                return "decimal";
            case "date":
                return "date";
            case "datetime":
                return "timestamp";
            case "boolean":
                return "boolean";
            case "text":
                return "text";
            default:
                return "varchar";
        }
    }

    String mapToJdbcType(String logicalType) {
        switch (logicalType.toLowerCase(java.util.Locale.ROOT)) {
            case "string":
                return "varchar";
            case "integer":
                return "integer";
            case "long":
                return "bigint";
            case "decimal":
                return "decimal";
            case "date":
                return "date";
            case "datetime":
                return "timestamp";
            case "boolean":
                return "boolean";
            case "text":
                return "clob";
            default:
                return "varchar";
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
