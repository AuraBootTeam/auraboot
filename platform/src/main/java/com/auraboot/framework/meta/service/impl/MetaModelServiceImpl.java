package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 模型元数据服务实现
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaModelServiceImpl extends BaseMetaService implements MetaModelService {

    private static final Pattern MODEL_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final int MAX_MODEL_CODE_LENGTH = 64;

    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final QueryBuilderService queryBuilderService;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissionAssignmentService;

    @Autowired
    @Lazy
    private SchemaManagementService schemaManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    @Lazy
    private com.auraboot.framework.meta.spi.MoneyFieldExpansionSpi moneyFieldTypeHandler;

    @Autowired
    private com.auraboot.framework.meta.handler.I18nFieldExpander i18nFieldExpander;

    @Autowired
    private RollUpFieldRegistry rollUpFieldRegistry;

    @Autowired
    @Lazy
    private com.auraboot.framework.meta.mapper.PageSchemaMapper pageSchemaMapper;

    @Override
    @Cacheable(value = "modelDefinitions", key = "#modelCode + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()", unless = "#result == null")
    public Optional<ModelDefinition> getModelDefinition(String modelCode) {
        validateModelCode(modelCode);
        logOperation("getModelDefinition", modelCode);
        
        // 直接使用 findCurrentByCode 方法，租户拦截器会自动添加 tenant_id 条件
        Model model = metaModelMapper.findCurrentByCode(modelCode);
        
        if (model != null) {
            // 转换Entity为DTO
            ModelDefinition modelDefinition = convertToModelDefinition(model);
            
            // 加载字段定义
            List<FieldDefinition> fields = loadFieldDefinitions(model.getId());
            modelDefinition.setFields(fields);
            
            // 加载关联关系
            List<RelationDefinition> relations = loadModelRelations(model.getId());
            modelDefinition.setRelations(relations);
            
            return Optional.of(modelDefinition);
        }
        
        return Optional.empty();
    }

    @Override
    public Optional<ModelDefinition> getModelDefinitionFromDb(String modelCode) {
        validateModelCode(modelCode);
        
        // 清除缓存后重新获取
        evictModelCache(modelCode);
        return getModelDefinition(modelCode);
    }

    @Override
    public String getTableName(String modelCode) {
        String tableName = getModelDefinition(modelCode)
                .map(ModelDefinition::getTableName)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
        // Defense-in-depth: validate table name to prevent DDL/SQL injection
        // even though table names are admin-configured, not user-input
        SqlSafetyUtils.validateIdentifier(tableName, "table name for model " + modelCode);
        return tableName;
    }

    @Override
    public List<FieldDefinition> getModelFields(String modelCode) {
        return getModelDefinition(modelCode)
                .map(ModelDefinition::getFields)
                .orElse(Collections.emptyList());
    }

    @Override
    public FieldDefinition getPrimaryKeyField(String modelCode) {
        // 系统保证 pid 是业务主键（VARCHAR(32) UUID）
        // id 是数据库物理主键（BIGINT 自增），业务层不直接使用
        return FieldDefinition.builder()
                .code("pid")
                .name("pid")
                .columnName("pid")
                .dataType("string")
                .primaryKey(true)
                .build();
    }

    @Override
    public List<FieldDefinition> getDisplayFields(String modelCode) {
        List<FieldDefinition> fields = getModelFields(modelCode);
        return fields.stream()
                .filter(field -> field.isDisplayField() || field.isPrimaryKey())
                .collect(Collectors.toList());
    }

    @Override
    public FieldDefinition getFieldDefinition(String modelCode, String fieldCode) {
        validateFieldCode(fieldCode);
        
        List<FieldDefinition> fields = getModelFields(modelCode);
        return fields.stream()
                .filter(field -> field.getCode().equals(fieldCode))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException("Field not found: " + fieldCode + " in model: " + modelCode));
    }

    @Override
    public String getColumnName(String modelCode, String fieldCode) {
        return getFieldDefinition(modelCode, fieldCode).getColumnName();
    }

    @Override
    public DataTypeMapping getFieldDataType(String modelCode, String fieldCode) {
        FieldDefinition field = getFieldDefinition(modelCode, fieldCode);
        return field.getDataTypeMapping();
    }

    @Override
    public List<ValidationRule> getFieldValidationRules(String modelCode, String fieldCode) {
        FieldDefinition field = getFieldDefinition(modelCode, fieldCode);
        return field.getValidationRules() != null ? field.getValidationRules() : Collections.emptyList();
    }

    @Override
    public List<RelationDefinition> getModelRelations(String modelCode) {
        return getModelDefinition(modelCode)
                .map(ModelDefinition::getRelations)
                .orElse(Collections.emptyList());
    }

    @Override
    public RelationDefinition getRelationDefinition(String modelCode, String relationName) {
        List<RelationDefinition> relations = getModelRelations(modelCode);
        return relations.stream()
                .filter(relation -> relation.getName().equals(relationName))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException("Relation not found: " + relationName + " in model: " + modelCode));
    }

    @Override
    public RelationDefinition getReverseRelation(String modelCode, String relationName) {
        RelationDefinition relation = getRelationDefinition(modelCode, relationName);
        
        // 查找反向关联
        String targetModel = relation.getTargetModel();
        List<RelationDefinition> targetRelations = getModelRelations(targetModel);
        
        return targetRelations.stream()
                .filter(rel -> rel.getTargetModel().equals(modelCode) && 
                              rel.getSourceField().equals(relation.getTargetField()) &&
                              rel.getTargetField().equals(relation.getSourceField()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<IndexDefinition> getModelIndexes(String modelCode) {
        Optional<ModelDefinition> modelOpt = getModelDefinition(modelCode);
        
        if (modelOpt.isPresent()) {
            // TODO: 从数据库加载索引定义
            return Collections.emptyList();
        }
        
        return Collections.emptyList();
    }

    @Override
    public List<ConstraintDefinition> getModelConstraints(String modelCode) {
        Optional<ModelDefinition> modelOpt = getModelDefinition(modelCode);
        
        if (modelOpt.isPresent()) {
            // TODO: 从数据库加载约束定义
            return Collections.emptyList();
        }
        
        return Collections.emptyList();
    }

    @Override
    public List<IndexInfo> getFieldIndexes(String modelCode, String fieldCode) {
        // TODO: 实现字段索引查询
        return Collections.emptyList();
    }

    @Override
    @Deprecated
    public QueryBuilderService.QueryBuilder buildBaseQuery(String modelCode, QueryBuilderService.QueryType queryType) {
        ModelDefinition model = getModelDefinition(modelCode)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
        
        return queryBuilderService.buildBaseQuery(model, queryType);
    }

    @Override
    @Deprecated
    public QueryBuilderService.QueryBuilder buildConditionQuery(String modelCode, List<QueryCondition> conditions) {
        ModelDefinition model = getModelDefinition(modelCode)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
        
        return queryBuilderService.buildConditionQuery(model, conditions);
    }

    @Override
    @Deprecated
    public String buildOrderByClause(String modelCode, List<SortField> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return "";
        }
        
        // 验证排序字段是否存在于模型中
        List<FieldDefinition> modelFields = getModelFields(modelCode);
        Set<String> validFields = modelFields.stream()
                .map(FieldDefinition::getCode)
                .collect(Collectors.toSet());
        
        List<String> orderClauses = sortFields.stream()
                .filter(sort -> validFields.contains(sort.getFieldName()))
                .map(sort -> {
                    String columnName = getColumnName(modelCode, sort.getFieldName());
                    return columnName + " " + sort.getDirection();
                })
                .collect(Collectors.toList());
        
        return String.join(", ", orderClauses);
    }

    @Override
    @Deprecated
    public String buildPaginationQuery(String baseQuery, PaginationRequest pageRequest) {
        if (pageRequest == null) {
            return baseQuery;
        }
        
        int pageSize = Math.min(pageRequest.getPageSize(), 1000); // 限制最大页面大小
        int pageNum = Math.max(1, pageRequest.getPageNum());
        int offset = (pageNum - 1) * pageSize;
        
        return baseQuery + " LIMIT " + pageSize + " OFFSET " + offset;
    }

    @Override
    @CacheEvict(value = "modelDefinitions", key = "#modelCode + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()")
    public void refreshModelCache(String modelCode) {
        log.info("Refreshing model cache for: {} in tenant: {}", modelCode, getCurrentTenantId());
    }

    @Override
    @CacheEvict(value = {"modelDefinitions", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public void clearAllCache() {
        log.info("Clearing all metadata cache for tenant: {}", getCurrentTenantId());
    }

    @Override
    public void preloadModels(List<String> modelCodes) {
        if (modelCodes == null || modelCodes.isEmpty()) {
            return;
        }
        
        log.info("Preloading models: {} for tenant: {}", modelCodes, getCurrentTenantId());
        
        for (String modelCode : modelCodes) {
            try {
                getModelDefinition(modelCode);
            } catch (Exception e) {
                log.warn("Failed to preload model: {}, error: {}", modelCode, e.getMessage());
            }
        }
    }

    @Override
    public MetadataValidationResult validateModelMetadata(String modelCode) {
        try {
            Optional<ModelDefinition> modelOpt = getModelDefinition(modelCode);
            
            if (modelOpt.isEmpty()) {
                return MetadataValidationResult.builder()
                        .valid(false)
                        .modelCode(modelCode)
                        .errors(List.of("Model not found: " + modelCode))
                        .summary("Model validation failed")
                        .build();
            }
            
            ModelDefinition model = modelOpt.get();
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            // 验证模型基本信息
            if (model.getTableName() == null || model.getTableName().trim().isEmpty()) {
                errors.add("Table name is required");
            }
            
            // 验证字段定义
            if (model.getFields() == null || model.getFields().isEmpty()) {
                warnings.add("No fields defined for model");
            } else {
                validateFields(model.getFields(), errors, warnings);
            }
            
            // 验证主键
            boolean hasPrimaryKey = model.getFields().stream().anyMatch(FieldDefinition::isPrimaryKey);
            if (!hasPrimaryKey) {
                errors.add("Primary key field is required");
            }
            
            return MetadataValidationResult.builder()
                    .valid(errors.isEmpty())
                    .modelCode(modelCode)
                    .errors(errors)
                    .warnings(warnings)
                    .summary(errors.isEmpty() ? "Model validation passed" : "Model validation failed")
                    .build();
                    
        } catch (Exception e) {
            return MetadataValidationResult.builder()
                    .valid(false)
                    .modelCode(modelCode)
                    .errors(List.of("Validation error: " + e.getMessage()))
                    .summary("Model validation failed with exception")
                    .build();
        }
    }

    @Override
    public boolean isModelExists(String modelCode) {
        try {
            return getModelDefinition(modelCode).isPresent();
        } catch (Exception e) {
            log.warn("Error checking model existence: {}, error: {}", modelCode, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isFieldExists(String modelCode, String fieldCode) {
        try {
            getFieldDefinition(modelCode, fieldCode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 模型管理 CRUD 操作 ====================

    @Override
    public MetaModelDTO create(MetaModelCreateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "创建请求不能为空");
        }
        


            return createDirectly(request);

    }
    

    
    /**
     * 直接创建模型(开发阶段保留)
     */
    private MetaModelDTO createDirectly(MetaModelCreateRequest request) {
        log.info("直接创建模型(非Git-First): {}", request.getCode());

        // Validate code is non-blank
        if (!StringUtils.hasText(request.getCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "模型编码不能为空");
        }

        // Validate code format: lowercase letters, numbers, underscores only
        String code = request.getCode();
        if (code.length() > MAX_MODEL_CODE_LENGTH) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "Model code must not exceed " + MAX_MODEL_CODE_LENGTH + " characters");
        }
        if (!MODEL_CODE_PATTERN.matcher(code).matches()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "Model code must start with a lowercase letter and contain only lowercase letters, numbers, and underscores: " + code);
        }

        // Check code uniqueness
        if (!isCodeUnique(request.getCode(), null)) {
            throw new IllegalArgumentException("Model code already exists: " + request.getCode());
        }
        
        // Create Model entity
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getCurrentTenantId());

        model.setCode(request.getCode());
        
        // Merge extension data: start from request.extension (preserves softDelete, etc.)
        Map<String, Object> extensionData = request.getExtension() != null
            ? new HashMap<>(request.getExtension()) : new HashMap<>();
        extensionData.put("displayName", request.getDisplayName());
        extensionData.put("description", request.getDescription());
        extensionData.put("modelType", request.getModelType());
        
        // Create ExtensionBean object and set it
        com.auraboot.framework.meta.entity.payload.ExtensionBean extension = 
            new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(extensionData);
        extension.validate();
        model.setExtension(extension);

        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(com.auraboot.framework.meta.constant.Status.DRAFT.getCode());
        model.setCreatedAt(java.time.Instant.now());
        model.setUpdatedAt(java.time.Instant.now());
        model.setDeletedFlag(false);

        // Set tableName from request (first-class field, not extension)
        if (StringUtils.hasText(request.getTableName())) {
            model.setTableName(request.getTableName());
        } else if (extensionData.containsKey("tableName")) {
            // Legacy fallback: extract from extension for backward compat during migration
            model.setTableName((String) extensionData.get("tableName"));
            extensionData.remove("tableName");
        }

        // Extract modelCategory from request or extension
        if (StringUtils.hasText(request.getModelCategory())) {
            model.setModelCategory(request.getModelCategory());
        } else if (extensionData.containsKey("modelCategory")) {
            model.setModelCategory((String) extensionData.get("modelCategory"));
        }

        // Set plugin_pid if provided
        if (StringUtils.hasText(request.getPluginPid())) {
            model.setPluginPid(request.getPluginPid());
        }

        // Save to database
        int result = metaModelMapper.insert(model);
        if (result <= 0) {
            throw new MetaServiceException("Failed to create model");
        }
        
        log.info("模型创建成功: {}", model.getPid());

        // Auto-bind system fields (id, pid, created_at, updated_at)
        autoBindSystemFields(model.getId());

        // Convert to DTO
        MetaModelDTO dto = convertToMetaModelDTO(model);
        
        // 自动分配 permissions
        autoPermissionAssignmentService.autoAssignPermissions(request.getCode(), null);
        log.info("Auto permission assignment completed for model: {}", request.getCode());
        
        return dto;
    }
    
    /**
     * Auto-bind system fields to a newly created model.
     * System fields: id, pid, created_at, updated_at
     */
    private void autoBindSystemFields(Long modelId) {
        Set<String> systemFieldCodes = SystemFieldConstants.AUTO_BIND;

        for (String fieldCode : systemFieldCodes) {
            Field field = metaFieldMapper.findCurrentByCode(fieldCode);
            if (field == null) {
                log.warn("System field not found: {}, skipping auto-bind", fieldCode);
                continue;
            }

            // Check if already bound (prevent duplicates)
            if (fieldBindingMapper.countByModelAndField(modelId, field.getId()) > 0) {
                log.debug("System field already bound: modelId={}, fieldCode={}", modelId, fieldCode);
                continue;
            }

            // Create system field binding
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setModelId(modelId);
            binding.setFieldId(field.getId());
            binding.setFieldOrder(getSystemFieldOrder(fieldCode));
            binding.setRequired("id".equals(fieldCode) || "pid".equals(fieldCode));
            binding.setVisible(true);
            binding.setEditable(false); // System fields are not editable
            binding.setIsSystemBinding(true); // Mark as system binding
            binding.setTenantId(MetaContext.getCurrentTenantId());
            binding.setCreatedAt(Instant.now());
            binding.setUpdatedAt(Instant.now());

            fieldBindingMapper.insert(binding);
            log.info("Auto-bound system field: modelId={}, fieldCode={}", modelId, fieldCode);
        }
    }

    /**
     * Get the sort order for system fields.
     * System fields have negative orders to appear first.
     */
    private int getSystemFieldOrder(String fieldCode) {
        return switch (fieldCode) {
            case "id" -> -1000;
            case "pid" -> -999;
            case "created_at" -> -998;
            case "updated_at" -> -997;
            default -> 0;
        };
    }

    /**
     * 验证创建请求
     */
    private void validateCreateRequest(MetaModelCreateRequest request) {
        if (!StringUtils.hasText(request.getCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "模型编码不能为空");
        }
        if (!StringUtils.hasText(request.getDisplayName())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "模型显示名称不能为空");
        }
    }
    
    /**
     * 验证编码唯一性
     * For versioning system: only check if CURRENT version with this code exists
     * (excluding the record being updated if excludePid is provided)
     */
    private void validateCodeUnique(String code, String excludePid) {
        // Check if a current version with this code exists
        Model currentModel = metaModelMapper.findCurrentByCode(code);

        // If no current version exists, code is available
        if (currentModel == null) {
            return;
        }

        // If current version exists, check if it's the one being updated
        if (StringUtils.hasText(excludePid)) {
            Model excludeModel = metaModelMapper.findByPid(excludePid);
            if (excludeModel != null && currentModel.getId().equals(excludeModel.getId())) {
                // It's the same record being updated, allow it
                return;
            }
        }

        // Current version exists and it's not the one being updated
        throw new ValidationException(ResponseCode.CommonValidationFailed,
            "模型编码已存在: " + code);
    }
    
    /**
     * 根据编码查找模型
     */
    public MetaModelDTO findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "模型编码不能为空");
        }



        // 查找当前版本模型
        Model model = metaModelMapper.findCurrentByCode(code);

        if (model == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "模型不存在: " + code);
        }
        return convertToMetaModelDTO(model);
    }

    @Override
    public MetaModelDTO findByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            return null;
        }

        log.debug("查询模型: pid={}", pid);



        try {
            // 使用租户上下文验证查找
            Model model = findEntityByPid(pid);
            return convertToMetaModelDTO(model);
        } catch (ValidationException e) {
            // 如果模型不存在或不属于当前租户，返回 null
            log.debug("模型不存在或不属于当前租户: pid={}", pid);
            return null;
        }
    }

    @Override
    public void delete(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID不能为空");
        }

        log.info("删除模型: {}", pid);



        // 查找现有记录（带租户上下文验证）
        Model model = findEntityByPid(pid);

        // 检查是否可以删除
        validateCanDelete(model);

        deleteDirectly(model);
    }
    

    
    private void deleteDirectly(Model model) {
        log.info("直接删除模型(非Git-First): {}", model.getCode());
        
        // 软删除
        int result = metaModelMapper.deleteById(model.getId());
        if (result <= 0) {
            throw new MetaServiceException("Failed to delete model");
        }
        
        // 清除缓存
        refreshModelCache(model.getCode());
        
        log.info("模型删除成功: {}", model.getPid());
    }
    
    /**
     * 验证是否可以删除
     */
    private void validateCanDelete(Model model) {
        // 检查是否有绑定的字段
        int boundFieldCount = fieldBindingMapper.countFieldsByModelId(model.getId());
        if (boundFieldCount > 0) {
            throw new IllegalStateException("Cannot delete model with bound fields. Found " + boundFieldCount + " bound fields.");
        }
    }
    
    /**
     * 根据 PID 查找实体（带租户上下文验证）
     */
    private Model findEntityByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID 不能为空");
        }

        Model model = metaModelMapper.findByPid(pid);
        if (model == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "模型不存在: " + pid);
        }
        


        return model;
    }

    @Override
    public boolean isCodeUnique(String code, String excludePid) {
        // Convert excludePid to excludeId if needed
        Long excludeId = null;
        if (excludePid != null) {
            Model excludeModel = metaModelMapper.findByPid(excludePid);
            if (excludeModel != null) {
                excludeId = excludeModel.getId();
            }
        }
        
        int count = metaModelMapper.countByCode(code, excludeId);
        return count == 0;
    }

    // 私有辅助方法
    
    /**
     * 将MetaModel实体转换为ModelDefinition DTO
     */
    private ModelDefinition convertToModelDefinition(Model model) {
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
                .version(model.getVersion())
                .status(model.getStatus() != null ? model.getStatus() : null)
                .createdAt(DateUtil.toUtcLocalDateTime(model.getCreatedAt()))
                .updatedAt(DateUtil.toUtcLocalDateTime(model.getUpdatedAt()))
                .softDelete(resolveSoftDelete(model))
                .rules(loadCrossFieldRules(model))
                .extension(flattenExtension(model))
                .build();
    }

    /**
     * Hydrate the capabilities JSONB string into a ModelCapabilities value object.
     * Empty / null → ModelCapabilities.empty().
     */
    private ModelCapabilities parseCapabilities(String json) {
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

    /**
     * Normalize caller-supplied capabilities so that sortableFields / filterableFields
     * reflect the field-level sortable/filterable flags on {@link ModelDefinition#getFields()}.
     *
     * Per design §3.3: 字段级 sortable/filterable 是编辑态 UI 输入，capabilities 白名单是运行时事实.
     * Any caller-supplied whitelist is OVERRIDDEN here.
     */
    private ModelCapabilities normalizeCapabilities(ModelDefinition def) {
        ModelCapabilities raw = def.getCapabilities() != null
            ? def.getCapabilities() : ModelCapabilities.empty();

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

    @Override
    @Transactional
    public ModelDefinition saveDefinition(ModelDefinition def) {
        if (def == null || !StringUtils.hasText(def.getCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "ModelDefinition.code must not be blank");
        }

        // Normalize capabilities first so the persisted value reflects the runtime truth.
        ModelCapabilities normalized = normalizeCapabilities(def);
        def.setCapabilities(normalized);

        String sourceType = StringUtils.hasText(def.getSourceType()) ? def.getSourceType() : "physical";
        String capabilitiesJson;
        try {
            capabilitiesJson = objectMapper.writeValueAsString(normalized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MetaServiceException("Failed to serialize capabilities for model " + def.getCode(), e);
        }

        Model existing = metaModelMapper.findCurrentByCode(def.getCode());
        if (existing != null) {
            existing.setSourceType(sourceType);
            existing.setSourceRef(def.getSourceRef());
            existing.setCapabilities(capabilitiesJson);
            if (StringUtils.hasText(def.getTableName())) {
                existing.setTableName(def.getTableName());
            }
            if (StringUtils.hasText(def.getModelCategory())) {
                existing.setModelCategory(def.getModelCategory());
            }
            // Merge caller-supplied extension keys (e.g. endpointAdapter) into
            // the existing ExtensionBean's nested map, preserving displayName etc.
            if (def.getExtension() != null && !def.getExtension().isEmpty()) {
                ExtensionBean ext = existing.getExtension();
                if (ext == null) {
                    ext = new ExtensionBean();
                    existing.setExtension(ext);
                }
                Map<String, Object> inner = ext.getExtension();
                if (inner == null) {
                    inner = new HashMap<>();
                    ext.setExtension(inner);
                }
                inner.putAll(def.getExtension());
                ext.validate();
            }
            existing.setUpdatedAt(Instant.now());
            metaModelMapper.updateById(existing);
            return getDefinitionByCode(def.getCode());
        }

        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getCurrentTenantId());
        model.setCode(def.getCode());

        // Build extension with displayName/description when supplied.
        Map<String, Object> extensionData = new HashMap<>();
        if (StringUtils.hasText(def.getDisplayName())) {
            extensionData.put("displayName", def.getDisplayName());
        }
        if (StringUtils.hasText(def.getDescription())) {
            extensionData.put("description", def.getDescription());
        }
        if (StringUtils.hasText(def.getModelType())) {
            extensionData.put("modelType", def.getModelType());
        }
        // Merge caller-supplied extension keys (e.g. endpointAdapter).
        if (def.getExtension() != null && !def.getExtension().isEmpty()) {
            extensionData.putAll(def.getExtension());
        }
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(extensionData);
        extension.validate();
        model.setExtension(extension);

        model.setTableName(def.getTableName());
        model.setModelCategory(def.getModelCategory());
        model.setSourceType(sourceType);
        model.setSourceRef(def.getSourceRef());
        model.setCapabilities(capabilitiesJson);

        model.setVersion(def.getVersion() != null ? def.getVersion() : 1);
        model.setIsCurrent(true);
        model.setStatus(def.getStatus() != null ? def.getStatus()
            : com.auraboot.framework.meta.constant.Status.DRAFT.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        int inserted = metaModelMapper.insert(model);
        if (inserted <= 0) {
            throw new MetaServiceException("Failed to persist model definition: " + def.getCode());
        }

        return getDefinitionByCode(def.getCode());
    }

    @Override
    public ModelDefinition getDefinitionByCode(String code) {
        return getModelDefinitionFromDb(code).orElse(null);
    }

    /**
     * Load cross-field validation rules from model extension.rules
     */
    @SuppressWarnings("unchecked")
    private List<CrossFieldRule> loadCrossFieldRules(Model model) {
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
                log.warn("Failed to parse cross-field rules for model {}: {}", model.getCode(), e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Resolve table name:
     * 1. entity.tableName (first-class column)
     * 2. generated default "ab_dyn_{code}"
     */
    private String resolveTableName(Model model) {
        if (model.getTableName() != null && !model.getTableName().trim().isEmpty()) {
            return model.getTableName().trim();
        }
        return generateTableName(model.getCode());
    }

    /**
     * Flatten Model.extension into a single map so executors can read config like
     * {@code endpointAdapter} regardless of whether it sits in the nested
     * {@code extension.extension} payload or at the flat top level.
     * Flat keys override nested ones when both exist.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenExtension(Model model) {
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

    /**
     * Resolve softDelete flag from extension.softDelete.
     * When true, delete operations use UPDATE deleted_flag=true instead of physical DELETE,
     * and queries automatically filter out soft-deleted records.
     */
    private boolean resolveSoftDelete(Model model) {
        if (model.getExtension() != null) {
            Object sd = model.getExtension().get("softDelete");
            return Boolean.TRUE.equals(sd) || "true".equals(String.valueOf(sd));
        }
        return false;
    }
    
    /**
     * 加载字段定义（优化版 - 批量查询避免N+1问题）
     * 自动补充系统字段：id, pid, created_at, updated_at, tenant_id 等
     */
    private List<FieldDefinition> loadFieldDefinitions(Long modelId) {
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
                        if (binding != null && Boolean.TRUE.equals(binding.getRequired())) {
                            fd.setRequired(true);
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

    /**
     * 补充系统字段定义（如果不存在）
     * 系统字段包括：id, pid, created_at, updated_at, created_by, updated_by, tenant_id
     */
    private void appendSystemFieldsIfMissing(List<FieldDefinition> fields, Set<String> existingCodes) {
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
    
    /**
     * 加载模型关联关系
     */
    private List<RelationDefinition> loadModelRelations(Long modelId) {
        // TODO: 从数据库加载关联关系
        return Collections.emptyList();
    }
    
    /**
     * 根据模型编码生成表名
     * 使用独立表模式，每个模型对应一个独立的表
     */
    private String generateTableName(String modelCode) {
        return SystemFieldConstants.generateTableName(modelCode);
    }



    /**
     * 将MetaModel实体转换为MetaModelDTO
     */
    private MetaModelDTO convertToMetaModelDTO(Model model) {
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
                .extension(convertExtensionToMap(model.getExtension()))
                .version(model.getVersion())
                .isCurrent(model.getIsCurrent())
                .status(model.getStatus() != null ? model.getStatus() : null)
                .createdAt(DateUtil.toUtcLocalDateTime(model.getCreatedAt()))
                .updatedAt(DateUtil.toUtcLocalDateTime(model.getUpdatedAt()))
                .build();
    }

    /**
     * Convert ExtensionBean to a flat Map for DTO.
     * Merges nested "extension" sub-map and top-level dynamic properties.
     */
    private Map<String, Object> convertExtensionToMap(ExtensionBean bean) {
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

    /**
     * 将FieldEntity转换为FieldDefinition
     */
    private FieldDefinition convertToFieldDefinition(Field field, Integer sortOrder) {
        if (field == null) {
            return null;
        }

        // 从feature中提取字段属性
        FieldFeatureBean feature = field.getFeature();
        Map<String, Object> extensionMap = new HashMap<>();
        if (field.getExtension() != null && field.getExtension().getExtension() != null) {
            extensionMap = field.getExtension().getExtension();
        }
        
        return FieldDefinition.builder()
                .code(field.getCode())
                .name(field.getCode())
                .displayName((String) extensionMap.get("displayName"))
                .description((String) extensionMap.get("description"))
                .dataType(field.getDataType())
                .columnName(generateColumnName(field.getCode()))
                .required(feature != null ? Boolean.TRUE.equals(feature.getRequired()) : false)
                .primaryKey(Boolean.TRUE.equals(extensionMap.get("primaryKey")) || Boolean.TRUE.equals(extensionMap.get("isPrimaryKey")))
                .unique(feature != null ? Boolean.TRUE.equals(feature.getUnique()) : false)
                .displayField(Boolean.TRUE.equals(extensionMap.get("displayField")))
                .defaultValue(feature != null ? feature.getDefaultValue() : null)
                .maxLength((Integer) extensionMap.get("maxLength"))
                .minLength((Integer) extensionMap.get("minLength"))
                .maxValue(extensionMap.get("maxValue"))
                .minValue(extensionMap.get("minValue"))
                .format((String) extensionMap.get("format"))
                .precision((Integer) extensionMap.get("precision"))
                .scale((Integer) extensionMap.get("scale"))
                .sortOrder(sortOrder)
                .dataTypeMapping(createDataTypeMapping(field.getDataType()))
                .validationRules(Collections.emptyList()) // TODO: 实现验证规则转换
                .virtualType(feature != null ? feature.getVirtualType() : null)
                .computeExpression(feature != null ? feature.getComputeExpression() : null)
                .computeDependencies(feature != null ? feature.getComputeDependencies() : null)
                .jsonbColumn((String) extensionMap.get("jsonbColumn"))
                .jsonbPath((String) extensionMap.get("jsonbPath"))
                .refTarget(convertRefTargetBeanToDto(field.getRefTarget()))
                .extraProps(extensionMap)
                .build();
    }
    
    private FieldDefinition.RefTarget convertRefTargetBeanToDto(FieldRefTargetBean bean) {
        if (bean == null || bean.getTargetEntity() == null) return null;
        return FieldDefinition.RefTarget.builder()
                .targetEntity(bean.getTargetEntity())
                .displayField(bean.getDisplayField())
                .build();
    }

    /**
     * 根据字段键生成列名
     */
    private String generateColumnName(String code) {
        // 简单的列名生成规则，将驼峰转换为下划线
        return code.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
    
    /**
     * 创建数据类型映射
     */
    private DataTypeMapping createDataTypeMapping(String dataType) {
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
    
    /**
     * 映射到Java类型
     */
    private String mapToJavaType(String logicalType) {
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
    
    /**
     * 映射到物理类型
     */
    private String mapToPhysicalType(String logicalType) {
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
    
    /**
     * 映射到JDBC类型
     */
    private String mapToJdbcType(String logicalType) {
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
    
    private void evictModelCache(String modelCode) {
        refreshModelCache(modelCode);
    }

    private void validateFields(List<FieldDefinition> fields, List<String> errors, List<String> warnings) {
        Set<String> fieldCodes = new HashSet<>();
        Set<String> columnNames = new HashSet<>();
        
        for (FieldDefinition field : fields) {
            // 检查字段编码重复
            if (!fieldCodes.add(field.getCode())) {
                errors.add("Duplicate field code: " + field.getCode());
            }
            
            // 检查列名重复
            if (!columnNames.add(field.getColumnName())) {
                errors.add("Duplicate column name: " + field.getColumnName());
            }
            
            // 检查字段名称
            if (field.getName() == null || field.getName().trim().isEmpty()) {
                warnings.add("Field name is empty for field: " + field.getCode());
            }
            
            // 检查数据类型
            if (field.getDataTypeMapping() == null) {
                errors.add("Data type mapping is required for field: " + field.getCode());
            }
        }
    }



    // ==================== 字段绑定管理实现 ====================

    @Override
    public boolean isModelExists(Long modelId) {
        try {
            return metaModelMapper.selectById(modelId) != null;
        } catch (Exception e) {
            log.error("检查模型存在性失败: modelId={}, error={}", modelId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isFieldExists(Long fieldId) {
        try {
            return metaFieldMapper.selectById(fieldId) != null;
        } catch (Exception e) {
            log.error("检查字段存在性失败: fieldId={}, error={}", fieldId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isFieldBoundToModel(Long modelId, Long fieldId) {
        try {
            return fieldBindingMapper.countByModelAndField(modelId, fieldId) > 0;
        } catch (Exception e) {
            log.error("检查字段绑定关系失败: modelId={}, fieldId={}, error={}", modelId, fieldId, e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {"modelFieldBindings", "fieldBindings", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public ModelFieldBinding bindFieldToModel(Long modelId, Long fieldId, Integer fieldOrder,
                                              Boolean required, Boolean visible, Boolean editable, String defaultValue,
                                              String validationRules, String displayConfig, String remarks) {
        
        logMetaOperation("bindFieldToModel", "modelId=" + modelId + ", fieldId=" + fieldId);
        
        try {
            // 验证模型是否存在
            if (!isModelExists(modelId)) {
                throw new MetaServiceException("模型不存在: " + modelId);
            }
            
            // 验证字段是否存在
            if (!isFieldExists(fieldId)) {
                throw new MetaServiceException("字段不存在: " + fieldId);
            }
            
            // 检查是否已经绑定
            if (isFieldBoundToModel(modelId, fieldId)) {
                throw new MetaServiceException("字段已经绑定到该模型: modelId=" + modelId + ", fieldId=" + fieldId);
            }
            
            ModelFieldBinding binding = new ModelFieldBinding();
            binding.setModelId(modelId);
            binding.setFieldId(fieldId);
            binding.setFieldOrder(fieldOrder != null ? fieldOrder : 0);
            binding.setRequired(required != null ? required : false);
            binding.setVisible(visible != null ? visible : true);
            binding.setEditable(editable != null ? editable : true);
            binding.setDefaultValue(defaultValue);
            binding.setValidationRules(validationRules);
            binding.setDisplayConfig(displayConfig);
            binding.setRemarks(remarks);
            binding.setTenantId(MetaContext.getCurrentTenantId());

            binding.setCreatedAt(Instant.now());
            binding.setUpdatedAt(Instant.now());
            
            int result = fieldBindingMapper.insert(binding);
            if (result <= 0) {
                throw new MetaServiceException("绑定字段到模型失败");
            }
            
            log.info("字段绑定成功: bindingId={}, modelId={}, fieldId={}", binding.getId(), modelId, fieldId);

            // If the model is already PUBLISHED, execute ALTER TABLE ADD COLUMN
            Model model = metaModelMapper.selectById(modelId);
            if (model != null && model.isPublished()) {
                Field field = metaFieldMapper.selectById(fieldId);
                if (field != null) {
                    log.info("模型已发布，执行 ALTER TABLE ADD COLUMN: modelCode={}, fieldCode={}", model.getCode(), field.getCode());
                    schemaManagementService.addFieldToModel(model.getCode(), field.getCode());
                }
            }

            return binding;

        } catch (Exception e) {
            log.error("绑定字段到模型失败: modelId={}, fieldId={}, error={}", modelId, fieldId, e.getMessage(), e);
            throw new MetaServiceException("绑定字段到模型失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {"modelFieldBindings", "fieldBindings", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public boolean unbindFieldFromModel(Long modelId, Long fieldId) {
        logMetaOperation("unbindFieldFromModel", "modelId=" + modelId + ", fieldId=" + fieldId);

        try {
            // Check if this is a system binding - system fields cannot be unbound
            ModelFieldBinding existingBinding = fieldBindingMapper.findByModelAndField(
                modelId, fieldId, MetaContext.getCurrentTenantId());
            if (existingBinding != null && Boolean.TRUE.equals(existingBinding.getIsSystemBinding())) {
                throw new MetaServiceException("Cannot unbind system field, it is required by the system");
            }

            // If the model is already PUBLISHED, execute ALTER TABLE DROP COLUMN before unbinding
            Model model = metaModelMapper.selectById(modelId);
            if (model != null && model.isPublished()) {
                Field field = metaFieldMapper.selectById(fieldId);
                if (field != null) {
                    log.info("模型已发布，执行 ALTER TABLE DROP COLUMN: modelCode={}, fieldCode={}", model.getCode(), field.getCode());
                    schemaManagementService.removeFieldFromModel(model.getCode(), field.getCode());
                }
            }

            int result = fieldBindingMapper.deleteByModelAndField(modelId, fieldId);
            boolean success = result > 0;
            
            if (success) {
                log.info("字段解绑成功: modelId={}, fieldId={}", modelId, fieldId);
            } else {
                log.warn("字段解绑失败，可能绑定关系不存在: modelId={}, fieldId={}", modelId, fieldId);
            }
            
            return success;
            
        } catch (Exception e) {
            log.error("解绑字段失败: modelId={}, fieldId={}, error={}", modelId, fieldId, e.getMessage(), e);
            throw new MetaServiceException("解绑字段失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Cacheable(value = "modelFieldBindings", key = "#modelId + '_' + #includeDetails + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()")
    public List<ModelFieldBinding> getModelFieldBindings(Long modelId, Boolean includeDetails) {
        logMetaOperation("getModelFieldBindings", "modelId=" + modelId + ", includeDetails=" + includeDetails);
        
        try {
            List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(modelId);
            
            if (includeDetails != null && includeDetails) {
                // 如果需要详细信息，可以在这里加载字段的详细信息
                // 这里简化处理，直接返回绑定关系
            }
            
            log.debug("获取模型字段绑定成功: modelId={}, count={}", modelId, bindings.size());
            return bindings;
            
        } catch (Exception e) {
            log.error("获取模型字段绑定失败: modelId={}, error={}", modelId, e.getMessage(), e);
            throw new MetaServiceException("获取模型字段绑定失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelFieldBinding> getFieldBinding(Long modelId, Long fieldId) {
        logMetaOperation("getFieldBinding", "modelId=" + modelId + ", fieldId=" + fieldId);
        
        try {
            ModelFieldBinding binding = fieldBindingMapper.selectByModelAndField(modelId, fieldId);
            return Optional.ofNullable(binding);
            
        } catch (Exception e) {
            log.error("获取字段绑定关系失败: modelId={}, fieldId={}, error={}", modelId, fieldId, e.getMessage(), e);
            throw new MetaServiceException("获取字段绑定关系失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {"modelFieldBindings", "fieldBindings", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public ModelFieldBinding updateFieldBinding(ModelFieldBinding binding) {
        logMetaOperation("updateFieldBinding", "bindingId=" + binding.getId());
        
        try {
            binding.setUpdatedAt(Instant.now());
            
            int result = fieldBindingMapper.updateById(binding);
            if (result <= 0) {
                throw new MetaServiceException("更新字段绑定关系失败");
            }
            
            log.info("字段绑定关系更新成功: bindingId={}", binding.getId());
            return binding;
            
        } catch (Exception e) {
            log.error("更新字段绑定关系失败: bindingId={}, error={}", binding.getId(), e.getMessage(), e);
            throw new MetaServiceException("更新字段绑定关系失败: " + e.getMessage(), e);
        }
    }


    
    // ==================== Git-First 辅助方法 ====================
    
    /**
     * 构建 DSL 文件路径
     *
     * @param code 模型编码
     * @return DSL 文件路径
     */
    private String buildDslPath(String code) {
        return String.format("tenant-%d/dsl/models/%s.json",
            MetaContext.getCurrentTenantId(), code);
    }
    


    // ==================== 版本管理实现 ====================

    @Override
    public List<MetaModelDTO> getVersionHistory(String code) {
        log.info("获取模型版本历史: code={}", code);
        
        // 查询所有版本
        List<Model> versions = metaModelMapper.findAllVersionsByCode(code);
        
        // 转换为DTO
        return versions.stream()
                .map(this::convertToMetaModelDTO)
                .collect(Collectors.toList());
    }

    @Override
    public MetaModelDTO getVersionDetail(String code, Integer version) {
        log.info("获取模型版本详情: code={}, version={}", code, version);
        
        Model model = metaModelMapper.findByCodeAndVersion(code, version);
        if (model == null) {
            throw new MetaServiceException("模型版本不存在: code=" + code + ", version=" + version);
        }
        
        return convertToMetaModelDTO(model);
    }

    @Override
    public Map<String, Object> compareVersions(String code, Integer v1, Integer v2) {
        log.info("对比模型版本: code={}, v1={}, v2={}", code, v1, v2);
        
        // 获取两个版本的模型
        Model model1 = metaModelMapper.findByCodeAndVersion(code, v1);
        Model model2 = metaModelMapper.findByCodeAndVersion(code, v2);
        
        if (model1 == null || model2 == null) {
            throw new MetaServiceException("版本不存在");
        }
        
        // 构建差异对象
        Map<String, Object> diff = new HashMap<>();
        diff.put("code", code);
        diff.put("v1", v1);
        diff.put("v2", v2);
        
        // 对比基本信息
        List<Map<String, Object>> changes = new ArrayList<>();
        
        // 对比显示名称
        if (!Objects.equals(model1.getDisplayName(), model2.getDisplayName())) {
            changes.add(Map.of(
                "field", "displayName",
                "oldValue", model1.getDisplayName() != null ? model1.getDisplayName() : "",
                "newValue", model2.getDisplayName() != null ? model2.getDisplayName() : ""
            ));
        }
        
        // 对比描述
        if (!Objects.equals(model1.getDescription(), model2.getDescription())) {
            changes.add(Map.of(
                "field", "description",
                "oldValue", model1.getDescription() != null ? model1.getDescription() : "",
                "newValue", model2.getDescription() != null ? model2.getDescription() : ""
            ));
        }
        
        // 对比模型类型
        if (!Objects.equals(model1.getModelType(), model2.getModelType())) {
            changes.add(Map.of(
                "field", "modelType",
                "oldValue", model1.getModelType() != null ? model1.getModelType() : "",
                "newValue", model2.getModelType() != null ? model2.getModelType() : ""
            ));
        }
        
        // 对比状态
        if (!Objects.equals(model1.getStatus(), model2.getStatus())) {
            changes.add(Map.of(
                "field", "status",
                "oldValue", model1.getStatus() != null ? model1.getStatus() : "",
                "newValue", model2.getStatus() != null ? model2.getStatus() : ""
            ));
        }
        
        diff.put("changes", changes);
        diff.put("hasChanges", !changes.isEmpty());

        return diff;
    }

    @Override
    @Transactional
    public MetaModelDTO rollbackToVersion(String code, Integer version) {
        log.info("回滚模型到指定版本: code={}, version={}", code, version);

        // 1. Get target version
        Model targetModel = metaModelMapper.findByCodeAndVersion(code, version);
        if (targetModel == null) {
            throw new IllegalArgumentException("目标版本不存在: " + code + " v" + version);
        }

        // 2. Mark all versions as non-current
        int cleared = metaModelMapper.clearCurrentFlag(code);
        log.debug("清除当前版本标记: code={}, count={}", code, cleared);

        // 3. Set target version as current
        int updated = metaModelMapper.setCurrentVersion(targetModel.getId());
        if (updated == 0) {
            throw new MetaServiceException("设置当前版本失败: " + code);
        }
        log.debug("设置当前版本: id={}, version={}", targetModel.getId(), version);

        // 4. Clear cache
        refreshModelCache(code);
        log.debug("缓存已刷新: code={}", code);

        // 5. Return updated model
        Model currentModel = metaModelMapper.findCurrentByCode(code);
        return convertToMetaModelDTO(currentModel);
    }

    @Override
    public Map<String, Object> getStatistics() {
        log.info("获取模型统计信息");
        
        // 查询所有当前版本的模型
        List<Model> currentModels = metaModelMapper.findCurrentByTenant();
        
        // 统计总数
        long totalModels = currentModels.size();
        
        // 按状态统计
        Map<String, Long> byStatus = currentModels.stream()
                .collect(Collectors.groupingBy(
                    m -> m.getStatus() != null ? m.getStatus() : "unknown",
                    Collectors.counting()
                ));
        
        // 按类型统计
        Map<String, Long> byType = currentModels.stream()
                .collect(Collectors.groupingBy(
                    m -> m.getModelType() != null ? m.getModelType() : "unknown",
                    Collectors.counting()
                ));
        
        // 统计活跃模型（已发布状态）
        long activeModels = currentModels.stream()
                .filter(m -> StatusConstants.PUBLISHED.equals(m.getStatus()))
                .count();
        
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalModels", totalModels);
        statistics.put("activeModels", activeModels);
        statistics.put("modelsByStatus", byStatus);
        statistics.put("modelsByType", byType);
        statistics.put("timestamp", Instant.now().toString());

        return statistics;
    }

    @Override
    public PageResult<MetaModelDTO> searchModels(
            Integer page, Integer size, String keyword, String code, String displayName,
            String modelType, String status,  Boolean currentOnly) {

        log.info("分页查询模型列表: page={}, size={}, keyword={}, code={}, displayName={}, modelType={}, status={}",
                page, size, keyword, code, displayName, modelType, status);

        // Validate and set defaults
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;
        if (size > 1000) size = 1000; // Max size limit
        if (currentOnly == null) currentOnly = true;

        // If keyword is provided, use it for generic search; otherwise use specific filters
        String searchKeyword = keyword;
        if (searchKeyword == null || searchKeyword.trim().isEmpty()) {
            // Build keyword from code or displayName if provided
            if (code != null && !code.trim().isEmpty()) {
                searchKeyword = code;
            } else if (displayName != null && !displayName.trim().isEmpty()) {
                searchKeyword = displayName;
            }
        }

        // Calculate offset
        long offset = (long) (page - 1) * size;

        // Get total count
        long total = metaModelMapper.countByKeyword(searchKeyword, modelType, status, currentOnly);

        // Get page data
        List<Model> models = metaModelMapper.searchByKeyword(
                searchKeyword, modelType, status, currentOnly, offset, size);

        // Convert to DTOs
        List<MetaModelDTO> dtos = models.stream()
                .map(this::convertToMetaModelDTO)
                .collect(Collectors.toList());

        // Build page result
        PageResult<MetaModelDTO> result = new PageResult<>();
        result.setRecords(dtos);
        result.setPageInfo(total, (long) size, (long) page);

        log.info("模型列表查询完成: total={}, page={}, size={}", total, page, size);
        return result;
    }

    @Override
    public Map<String, Object> validateModelData(Map<String, Object> modelData) {
        log.info("验证模型数据");
        
        Map<String, Object> result = new HashMap<>();
        Map<String, String> errors = new HashMap<>();
        
        // 验证必填字段
        if (!modelData.containsKey("code") || modelData.get("code") == null || 
            modelData.get("code").toString().trim().isEmpty()) {
            errors.put("code", "模型编码不能为空");
        }
        
        if (!modelData.containsKey("displayName") || modelData.get("displayName") == null || 
            modelData.get("displayName").toString().trim().isEmpty()) {
            errors.put("displayName", "显示名称不能为空");
        }
        
        // 验证编码格式（只能包含字母、数字和下划线）
        if (modelData.containsKey("code") && modelData.get("code") != null) {
            String code = modelData.get("code").toString();
            if (!code.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
                errors.put("code", "模型编码格式不正确，必须以字母开头，只能包含字母、数字和下划线");
            }
        }
        
        // 验证编码唯一性
        if (modelData.containsKey("code") && modelData.get("code") != null) {
            String code = modelData.get("code").toString();
            String excludePid = modelData.containsKey("pid") ? modelData.get("pid").toString() : null;
            
            if (!isCodeUnique(code, excludePid)) {
                errors.put("code", "模型编码已存在: " + code);
            }
        }
        
        // 验证模型类型
        if (modelData.containsKey("modelType") && modelData.get("modelType") != null) {
            String modelType = modelData.get("modelType").toString();
            List<String> validTypes = Arrays.asList("entity", "view", "aggregate", "value_object");
            if (!validTypes.contains(modelType)) {
                errors.put("modelType", "无效的模型类型: " + modelType);
            }
        }
        
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);

        return result;
    }

    // ==================== Publish/Unpublish ====================

    @Override
    @Transactional
    @CacheEvict(value = {"modelDefinitions", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public MetaModelDTO publish(String pid, String versionNote) {
        logMetaOperation("publish", "pid=" + pid);

        Model model = findEntityByPid(pid);

        // Validate: must be DRAFT
        if (!model.isDraft()) {
            throw new MetaServiceException("Only DRAFT models can be published, current status: " + model.getStatus());
        }

        // Skip table creation for VIEW models and models with skipTableCreation flag
        // (e.g., BPM system tables managed outside DSL schema management)
        if (model.isViewType() || model.isSkipTableCreation()) {
            log.info("Publishing model (no table creation): pid={}, code={}, reason={}",
                    pid, model.getCode(),
                    model.isViewType() ? "VIEW model" : "skipTableCreation=true");
        } else {
            // Validate: must have at least one field binding
            List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(model.getId());
            if (bindings == null || bindings.isEmpty()) {
                throw new MetaServiceException("Model must have at least one field bound before publishing");
            }

            // Auto-mark searchable fields if none are explicitly marked
            autoMarkSearchableFields(model.getId(), bindings);

            // Expand MONEY type fields (auto-create _base fields, currency headers, binding rules)
            // moneyFieldTypeHandler is provided by the enterprise finance module; absent in core-only deploys.
            if (moneyFieldTypeHandler != null) {
                try {
                    List<String> expandedFields = moneyFieldTypeHandler.expandMoneyFields(model);
                    if (!expandedFields.isEmpty()) {
                        log.info("MONEY field expansion created {} field(s) for model {}: {}",
                                expandedFields.size(), model.getCode(), String.join(", ", expandedFields));
                    }
                } catch (Exception e) {
                    log.warn("MONEY field expansion failed for model {} (non-blocking): {}",
                            model.getCode(), e.getMessage());
                }
            }

            // Expand i18n-enabled fields (auto-create _en_us, _ja_jp, _ko_kr companion fields)
            try {
                List<String> i18nFields = i18nFieldExpander.expandI18nFields(model);
                if (!i18nFields.isEmpty()) {
                    log.info("i18n field expansion created {} field(s) for model {}: {}",
                            i18nFields.size(), model.getCode(), String.join(", ", i18nFields));
                }
            } catch (Exception e) {
                log.warn("i18n field expansion failed for model {} (non-blocking): {}",
                        model.getCode(), e.getMessage());
            }

            // Create table via SchemaManagementService
            log.info("Publishing model: pid={}, code={}", pid, model.getCode());
            SchemaOperationResult schemaResult = schemaManagementService.createTableByModel(model.getCode());
            if (schemaResult == null || !schemaResult.isSuccess()) {
                String errorMessage = schemaResult != null && schemaResult.getErrorMessage() != null
                        ? schemaResult.getErrorMessage()
                        : "unknown schema creation error";
                throw new MetaServiceException("Failed to publish model because schema creation failed: " + errorMessage);
            }
        }

        // Update model status
        model.setStatus(StatusConstants.PUBLISHED);
        model.setIsCurrent(true);
        model.setUpdatedAt(Instant.now());
        metaModelMapper.updateById(model);

        log.info("Model published successfully: pid={}, code={}", pid, model.getCode());

        // Auto-create hierarchical permissions for the published model
        autoPermissionAssignmentService.autoAssignPermissions(model.getCode(), null);
        log.info("Hierarchical permissions created for model: {}", model.getCode());

        // Invalidate roll-up field registry (model fields may include rollUp config)
        rollUpFieldRegistry.invalidateModel(model.getCode());

        // Auto-create standard CRUD page schemas (list/form/detail) if they don't exist
        autoCreateDefaultPages(model);

        return convertToMetaModelDTO(model);
    }

    /**
     * Auto-creates standard CRUD page schemas (list, form, detail) for a newly published model.
     * Only creates pages that do not already exist — fully idempotent.
     *
     * <p>Pages are created as 'published' so they are immediately accessible via /api/pages/key/{pageKey}.
     * Blocks follow the V2 flat format (kind + blocks array, no dslSchema nesting).</p>
     *
     * @param model the published model
     */
    private void autoCreateDefaultPages(Model model) {
        String modelCode = model.getCode();
        Long tenantId = model.getTenantId();
        Instant now = Instant.now();

        record PageSpec(String kind, String pageKey, String blocks) {}

        List<PageSpec> specs = List.of(
            new PageSpec("list", modelCode + "_list",
                "[{\"blockType\":\"toolbar\"},{\"blockType\":\"filters\"},{\"blockType\":\"table\"}]"),
            new PageSpec("form", modelCode + "_form",
                "[{\"blockType\":\"form-section\"}]"),
            new PageSpec("detail", modelCode + "_detail",
                "[{\"blockType\":\"form-section\"},{\"blockType\":\"tabs\"}]")
        );

        for (PageSpec spec : specs) {
            // Check existence by page_key (unique per tenant+namespace)
            com.auraboot.framework.meta.entity.PageSchema existing =
                pageSchemaMapper.selectAnyByPageKey(spec.pageKey());
            if (existing != null) {
                log.debug("Page schema already exists, skipping auto-create: pageKey={}", spec.pageKey());
                continue;
            }

            // Build title JSONB: {"en": "<ModelCode> <Kind>", "zh-CN": "<ModelCode> <Kind>"}
            String titleLabel = modelCode + " " + spec.kind();
            String titleJson = "{\"en\":\"" + titleLabel + "\",\"zh-CN\":\"" + titleLabel + "\"}";

            int inserted = pageSchemaMapper.insertForPluginImport(
                UniqueIdGenerator.generate(),   // pid
                tenantId,                        // tenantId
                "published",                     // status
                spec.pageKey(),                  // pageKey
                modelCode,                       // modelCode
                spec.pageKey(),                  // name (same as pageKey)
                titleJson,                       // title
                null,                            // description
                spec.kind(),                     // kind
                "admin",                         // profile
                "{\"type\":\"stack\"}",          // layout
                spec.blocks(),                   // blocks
                2,                               // schemaVersion (V2 flat format)
                false,                           // isTemplate
                null,                            // templateCategory
                now,                             // publishedAt
                0,                               // sortWeight
                "{}",                            // extension
                null                             // pluginPid
            );

            if (inserted > 0) {
                log.info("Auto-created default page schema: pageKey={}, kind={}, modelCode={}",
                    spec.pageKey(), spec.kind(), modelCode);
            }
        }
    }

    /**
     * Auto-mark the first N STRING/TEXT fields as searchable if none are explicitly marked.
     * Called during model publish to ensure keyword search has fields to work with.
     */
    private void autoMarkSearchableFields(Long modelId, List<ModelFieldBinding> bindings) {
        // Check if any binding already has searchable=true
        boolean anySearchable = bindings.stream()
                .anyMatch(b -> Boolean.TRUE.equals(b.getSearchable()));
        if (anySearchable) {
            log.debug("Model {} already has searchable fields marked, skipping auto-mark", modelId);
            return;
        }

        // Load field entities to check data types
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .toList();
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        Map<Long, Field> fieldMap = fields.stream()
                .collect(java.util.stream.Collectors.toMap(Field::getId, f -> f));

        Set<String> searchableTypes = Set.of("string", "text", "enum", "dict");
        int marked = 0;
        int maxAutoSearchable = 5;

        for (ModelFieldBinding binding : bindings) {
            if (marked >= maxAutoSearchable) break;
            Field field = fieldMap.get(binding.getFieldId());
            if (field == null) continue;
            String dt = field.getDataType() != null ? field.getDataType().toUpperCase() : "";
            if (!searchableTypes.contains(dt)) continue;
            // Skip system fields
            String code = field.getCode();
            if (code == null) continue;
            if (Set.of("pid", "created_by", "updated_by", "tenant_id").contains(code)) continue;

            binding.setSearchable(true);
            binding.setUpdatedAt(Instant.now());
            fieldBindingMapper.updateById(binding);
            marked++;
            log.debug("Auto-marked field {} as searchable for model {}", code, modelId);
        }

        if (marked > 0) {
            log.info("Auto-marked {} fields as searchable for model {}", marked, modelId);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {"modelDefinitions", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public MetaModelDTO unpublish(String pid) {
        logMetaOperation("unpublish", "pid=" + pid);

        Model model = findEntityByPid(pid);

        // Validate: must be PUBLISHED
        if (!model.isPublished()) {
            throw new MetaServiceException("Only PUBLISHED models can be unpublished, current status: " + model.getStatus());
        }

        // Update status to DEPRECATED, keep the table (preserve data)
        model.setStatus(StatusConstants.DEPRECATED);
        model.setIsCurrent(false);
        model.setUpdatedAt(Instant.now());
        metaModelMapper.updateById(model);

        log.info("Model unpublished: pid={}, code={}", pid, model.getCode());
        return convertToMetaModelDTO(model);
    }

    @Override
    public DDLPreviewResult previewPublishDDL(String pid) {
        logMetaOperation("previewPublishDDL", "pid=" + pid);

        Model model = findEntityByPid(pid);
        return schemaManagementService.previewModelChanges(model.getCode());
    }
}
