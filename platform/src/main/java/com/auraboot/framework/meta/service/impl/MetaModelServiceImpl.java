package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.listener.SlaActivationListener;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.decision.rule.ConditionSpec;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionResult;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
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
import com.auraboot.framework.meta.entity.payload.FieldRuleSchemaBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;

import com.fasterxml.jackson.databind.JsonNode;
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
@SuppressWarnings("java/log-injection")
public class MetaModelServiceImpl extends BaseMetaService implements MetaModelService {

    private static final Pattern MODEL_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final int MAX_MODEL_CODE_LENGTH = 64;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final QueryBuilderService queryBuilderService;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissionAssignmentService;
    private final MetaDefinitionCacheService metaDefinitionCacheService;

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

    // env-layering PoC #16: native @Insert SQL bypasses MetaObjectHandler, so callers must
    // resolve env_id explicitly before insertForPluginImport.
    @Autowired
    @Lazy
    private com.auraboot.framework.environment.service.EnvironmentService environmentService;

    @Autowired(required = false)
    @Lazy
    private DecisionImpactService decisionImpactService;

    @Autowired(required = false)
    @Lazy
    private DecisionImpactAckService decisionImpactAckService;

    @Autowired(required = false)
    @Lazy
    private DecisionEvaluationService decisionEvaluationService;

    @Autowired(required = false)
    @Lazy
    private EventPolicyRuntimeService eventPolicyRuntimeService;

    @Autowired(required = false)
    @Lazy
    private AutomationService automationService;

    @Autowired(required = false)
    @Lazy
    private ProcessDeploymentService processDeploymentService;

    @Autowired(required = false)
    @Lazy
    private BpmRuleBindingRuntimeService bpmRuleBindingRuntimeService;

    @Autowired(required = false)
    @Lazy
    private SlaConfigService slaConfigService;

    @Autowired(required = false)
    @Lazy
    private SlaActivationListener slaActivationListener;

    @Autowired(required = false)
    @Lazy
    private SlaRecordService slaRecordService;

    @Autowired(required = false)
    @Lazy
    private PermissionEvaluator permissionEvaluator;

    @Override
    public Optional<ModelDefinition> getModelDefinition(String modelCode) {
        validateModelCode(modelCode);
        return metaDefinitionCacheService.getModelDefinition(
                modelCode,
                () -> loadModelDefinition(modelCode));
    }

    private Optional<ModelDefinition> loadModelDefinition(String modelCode) {
        logOperation("getModelDefinition", modelCode);

        // 直接使用 findCurrentByCode 方法，租户拦截器会自动添加 tenant_id 条件
        Model model = metaModelMapper.findCurrentByCode(modelCode);

        if (model != null) {
            // 转换Entity为DTO
            ModelDefinition modelDefinition = convertToModelDefinition(model);

            // 加载字段定义
            List<FieldDefinition> fields = loadFieldDefinitions(model.getId());
            fields = mergeDeclaredExtensionFields(modelDefinition, fields);
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
        // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
        log.info("Refreshing model cache for: {} in tenant: {}", logSafe(modelCode), getCurrentTenantId());
    }

    @Override
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
    public void clearAllCache() {
        log.info("Clearing all metadata cache for tenant: {}", getCurrentTenantId());
    }

    @Override
    public void preloadModels(List<String> modelCodes) {
        if (modelCodes == null || modelCodes.isEmpty()) {
            return;
        }
        
        log.info("Preloading models: {} for tenant: {}", logSafe(modelCodes), getCurrentTenantId());
        
        for (String modelCode : modelCodes) {
            try {
                getModelDefinition(modelCode);
            } catch (Exception e) {
                // §P1 per-model tolerance: preload is best-effort warm-up; one
                // missing or malformed model must not abort warming the others.
                log.warn("Failed to preload model: {}, error: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
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
            // §P4 wrap-as-result variant: validateModel is invoked from import flows
            // and DDL preview where a thrown exception would collapse a batch. Surface
            // the failure in the result; log with stack trace so root cause is visible
            // in observability.
            log.error("Model validation failed with exception for {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
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
            // exists-check semantics: any failure is treated as "not exists" so callers
            // (importer / re-import) can proceed to create. Real DB failures still
            // surface via the warn log + stack trace for ops triage.
            log.warn("Error checking model existence: {}, error: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return false;
        }
    }

    @Override
    public boolean isFieldExists(String modelCode, String fieldCode) {
        try {
            getFieldDefinition(modelCode, fieldCode);
            return true;
        } catch (Exception e) {
            // exists-check semantics: any failure is treated as "not exists"; logged
            // at debug so a real DB/connectivity failure remains observable when
            // troubleshooting unexpected re-creates during import.
            log.debug("Error checking field existence: model={}, field={}, error={}",
                    logSafe(modelCode), logSafe(fieldCode), logSafe(e.toString()));
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
     * Create a model row plus the auto-bound system fields.
     *
     * <p>Callers are responsible for orchestrating any custom field creation
     * (via {@link MetaFieldService#create}) and publishing the model
     * (via {@link #publish(String, String)}). This method does not honor any
     * field list or auto-publish flag from the request.
     */
    private MetaModelDTO createDirectly(MetaModelCreateRequest request) {
        log.info("直接创建模型(非Git-First): {}", logSafe(request.getCode()));

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

        // Agent-ready semantic fields (first-class columns on ab_meta_model)
        model.setSemanticDescription(request.getSemanticDescription());
        model.setDomainCategory(request.getDomainCategory());
        if (StringUtils.hasText(request.getDataSensitivity())) {
            model.setDataSensitivity(request.getDataSensitivity());
        }
        model.setLifecycleDescription(request.getLifecycleDescription());

        // Set plugin_pid if provided
        if (StringUtils.hasText(request.getPluginPid())) {
            model.setPluginPid(request.getPluginPid());
        }

        // Save to database
        int result = metaModelMapper.insert(model);
        if (result <= 0) {
            throw new MetaServiceException("Failed to create model");
        }
        
        log.info("模型创建成功: {}", logSafe(model.getPid()));

        // Auto-bind system fields (id, pid, created_at, updated_at)
        autoBindSystemFields(model.getId());

        // Convert to DTO
        MetaModelDTO dto = convertToMetaModelDTO(model);
        
        // 自动分配 permissions
        autoPermissionAssignmentService.autoAssignPermissions(request.getCode(), null);
        log.info("Auto permission assignment completed for model: {}", logSafe(request.getCode()));
        
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
                log.warn("System field not found: {}, skipping auto-bind", logSafe(fieldCode));
                continue;
            }

            // Check if already bound (prevent duplicates)
            if (fieldBindingMapper.countByModelAndField(modelId, field.getId()) > 0) {
                log.debug("System field already bound: modelId={}, fieldCode={}", modelId, logSafe(fieldCode));
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
            log.info("Auto-bound system field: modelId={}, fieldCode={}", modelId, logSafe(fieldCode));
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
     * Lookup the current version of a model by code.
     *
     * @return the DTO, or {@code null} if no model with the given code exists
     */
    @Override
    public MetaModelDTO findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        Model model = metaModelMapper.findCurrentByCode(code);
        if (model == null) {
            return null;
        }
        return convertToMetaModelDTO(model);
    }

    /**
     * Lookup the current version of a model by code, throwing when missing.
     */
    @Override
    public MetaModelDTO findByCodeOrThrow(String code) {
        if (!StringUtils.hasText(code)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "模型编码不能为空");
        }
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

        log.debug("查询模型: pid={}", logSafe(pid));



        try {
            // 使用租户上下文验证查找
            Model model = findEntityByPid(pid);
            return convertToMetaModelDTO(model);
        } catch (ValidationException e) {
            // 如果模型不存在或不属于当前租户，返回 null
            log.debug("模型不存在或不属于当前租户: pid={}", logSafe(pid));
            return null;
        }
    }

    @Override
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
    public void delete(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID不能为空");
        }

        log.info("删除模型: {}", logSafe(pid));



        // 查找现有记录（带租户上下文验证）
        Model model = findEntityByPid(pid);

        // 检查是否可以删除
        validateCanDelete(model);

        deleteDirectly(model);
    }
    

    
    private void deleteDirectly(Model model) {
        log.info("直接删除模型(非Git-First): {}", logSafe(model.getCode()));
        
        // 软删除
        int result = metaModelMapper.deleteById(model.getId());
        if (result <= 0) {
            throw new MetaServiceException("Failed to delete model");
        }
        
        // 清除缓存
        refreshModelCache(model.getCode());
        
        log.info("模型删除成功: {}", logSafe(model.getPid()));
    }
    
    /**
     * 验证是否可以删除
     */
    private void validateCanDelete(Model model) {
        // Only count user-bound (non-system, non-soft-deleted) fields.
        // System bindings (id/pid/created_at/updated_at) are auto-bound on
        // model creation and must not block deletion of an otherwise empty model.
        int boundFieldCount = fieldBindingMapper.countUserFieldsByModelId(model.getId());
        if (boundFieldCount > 0) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "Cannot delete model with bound fields. Found " + boundFieldCount + " bound fields.");
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
                .rules(loadCrossFieldRules(model))
                .extension(flatExt)
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

    @Override
    @Transactional
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
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
            if (def.getExtension() != null && !def.getExtension().isEmpty()) {
                inner.putAll(def.getExtension());
            }
            // Persist ModelDefinition.primaryKey into extension so it survives reloads.
            if (StringUtils.hasText(def.getPrimaryKey())) {
                inner.put("primaryKey", def.getPrimaryKey());
            }
            if (def.getFields() != null && !def.getFields().isEmpty()) {
                inner.put("fields", def.getFields());
            }
            ext.validate();
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
        // Persist ModelDefinition.primaryKey into extension so it survives reloads.
        if (StringUtils.hasText(def.getPrimaryKey())) {
            extensionData.put("primaryKey", def.getPrimaryKey());
        }
        if (def.getFields() != null && !def.getFields().isEmpty()) {
            extensionData.put("fields", def.getFields());
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
                // §P2 best-effort: malformed cross-field-rule JSON should not block
                // model load. Caller treats null as "no rules"; warn log surfaces
                // the bad config for the model owner to fix.
                log.warn("Failed to parse cross-field rules for model {}: {}", logSafe(model.getCode()), logSafe(e.getMessage()), e);
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

    private List<FieldDefinition> mergeDeclaredExtensionFields(
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
    /**
     * Materialize the model's navigable relations from its reference fields.
     *
     * <p>A relation is derived from each field whose {@code refTarget} declares a
     * {@link FieldRefTargetBean.BidirectionalConfig} (relation type + target entity, and — for
     * many-to-many — the junction table and its source/target FK columns). Models without such
     * fields yield an empty list, so {@code getModelDefinition} behaves exactly as before for them.
     * This is what makes the relation/sub-table runtime ({@code getRelationData} /
     * {@code createRelations} / {@code saveWithRelations} / inverse-field sync) reachable.
     */
    private List<RelationDefinition> loadModelRelations(Long modelId) {
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

    /**
     * Build a single {@link RelationDefinition} from a reference field, or {@code null} when the
     * field does not declare a bidirectional relation (target entity + parseable relation type).
     */
    private RelationDefinition buildRelationDefinition(Field field, String sourceModel, String sourceTable) {
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

    private RelationDefinition.RelationType parseRelationType(String raw) {
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
        Integer fieldCount = model.getId() != null
                ? fieldBindingMapper.countUserFieldsByModelId(model.getId())
                : 0;
        return convertToMetaModelDTO(model, fieldCount);
    }

    private MetaModelDTO convertToMetaModelDTO(Model model, Integer fieldCount) {
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

    private Map<Long, Integer> loadUserFieldCountsByModelId(List<Model> models) {
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
     * Flatten Field.extension into extraProps for runtime services.
     *
     * <p>Field extension data can be persisted either as the canonical nested
     * {@code {"extension": {...}}} payload or as flat dynamic properties.
     * Some legacy import and test setup paths also materialize a top-level
     * dynamic {@code extension} map. Runtime consumers such as field permission
     * evaluation should see one flat map regardless of the stored shape.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenFieldExtension(ExtensionBean bean) {
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

    /**
     * 将FieldEntity转换为FieldDefinition
     */
    private FieldDefinition convertToFieldDefinition(Field field, Integer sortOrder) {
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
                .columnName(generateColumnName(field.getCode()))
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
                .extraProps(extensionMap)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNestedMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private Integer readInteger(Map<String, Object> primary, Map<String, Object> constraints, String key) {
        Object raw = readValue(primary, constraints, key, key);
        if (raw instanceof Integer integer) {
            return integer;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Object readValue(Map<String, Object> primary, Map<String, Object> constraints, String primaryKey, String fallbackKey) {
        if (primary.containsKey(primaryKey)) {
            return primary.get(primaryKey);
        }
        return constraints.get(fallbackKey);
    }
    
    private FieldDefinition.RefTarget convertRefTargetBeanToDto(FieldRefTargetBean bean) {
        if (bean == null || bean.getTargetEntity() == null) return null;
        return FieldDefinition.RefTarget.builder()
                .targetEntity(bean.getTargetEntity())
                .targetTable(bean.getTargetTable())
                .valueField(bean.getValueField())
                .targetField(bean.getTargetField())
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
            // exists-check semantics (§P4 wrap-as-bool): caller treats false as
            // "not exists"; raised exception (DB down, schema mismatch) is surfaced
            // via error log + stack trace rather than thrown, since binding flows
            // batch-iterate over many ids and one DB hiccup must not abort the loop.
            log.error("检查模型存在性失败: modelId={}, error={}", modelId, logSafe(e.getMessage()), e);
            return false;
        }
    }

    @Override
    public boolean isFieldExists(Long fieldId) {
        try {
            return metaFieldMapper.selectById(fieldId) != null;
        } catch (Exception e) {
            // exists-check semantics: see existsModelById above for full pattern note.
            log.error("检查字段存在性失败: fieldId={}, error={}", fieldId, logSafe(e.getMessage()), e);
            return false;
        }
    }

    @Override
    public boolean isFieldBoundToModel(Long modelId, Long fieldId) {
        try {
            return fieldBindingMapper.countByModelAndField(modelId, fieldId) > 0;
        } catch (Exception e) {
            // exists-check semantics: see existsModelById above for full pattern note.
            log.error("检查字段绑定关系失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            return false;
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
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
                    log.info("模型已发布，执行 ALTER TABLE ADD COLUMN: modelCode={}, fieldCode={}",
                            logSafe(model.getCode()), logSafe(field.getCode()));
                    schemaManagementService.addFieldToModel(model.getCode(), field.getCode());
                }
            }

            return binding;

        } catch (Exception e) {
            log.error("绑定字段到模型失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            throw new MetaServiceException("绑定字段到模型失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
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
                    log.info("模型已发布，执行 ALTER TABLE DROP COLUMN: modelCode={}, fieldCode={}",
                            logSafe(model.getCode()), logSafe(field.getCode()));
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
            log.error("解绑字段失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
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
            log.error("获取模型字段绑定失败: modelId={}, error={}", modelId, logSafe(e.getMessage()), e);
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
            log.error("获取字段绑定关系失败: modelId={}, fieldId={}, error={}", modelId, fieldId, logSafe(e.getMessage()), e);
            throw new MetaServiceException("获取字段绑定关系失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
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
            log.error("更新字段绑定关系失败: bindingId={}, error={}", binding.getId(), logSafe(e.getMessage()), e);
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
        log.info("获取模型版本历史: code={}", logSafe(code));
        
        // 查询所有版本
        List<Model> versions = metaModelMapper.findAllVersionsByCode(code);
        
        // 转换为DTO
        return versions.stream()
                .map(this::convertToMetaModelDTO)
                .collect(Collectors.toList());
    }

    @Override
    public MetaModelDTO getVersionDetail(String code, Integer version) {
        log.info("获取模型版本详情: code={}, version={}", logSafe(code), version);
        
        Model model = metaModelMapper.findByCodeAndVersion(code, version);
        if (model == null) {
            throw new MetaServiceException("模型版本不存在: code=" + code + ", version=" + version);
        }
        
        return convertToMetaModelDTO(model);
    }

    @Override
    public Map<String, Object> compareVersions(String code, Integer v1, Integer v2) {
        log.info("对比模型版本: code={}, v1={}, v2={}", logSafe(code), v1, v2);
        
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
    @CacheEvict(value = {
            "modelDefinitions",
            "modelFieldBindings",
            "metaField",
            "viewModelFields",
            "viewModelSummary"
    }, allEntries = true)
    public MetaModelDTO rollbackToVersion(String code, Integer version) {
        log.info("回滚模型到指定版本: code={}, version={}", logSafe(code), version);

        // 1. Get target version
        Model targetModel = metaModelMapper.findByCodeAndVersion(code, version);
        if (targetModel == null) {
            throw new IllegalArgumentException("目标版本不存在: " + code + " v" + version);
        }

        // 2. Mark all versions as non-current
        int cleared = metaModelMapper.clearCurrentFlag(code);
        log.debug("清除当前版本标记: code={}, count={}", logSafe(code), cleared);

        // 3. Set target version as current
        int updated = metaModelMapper.setCurrentVersion(targetModel.getId());
        if (updated == 0) {
            throw new MetaServiceException("设置当前版本失败: " + code);
        }
        log.debug("设置当前版本: id={}, version={}", targetModel.getId(), version);

        // 4. Clear cache
        refreshModelCache(code);
        log.debug("缓存已刷新: code={}", logSafe(code));

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
            String modelType, String status, String sourceType, String sortField, String sortOrder, Boolean currentOnly) {

        log.info(
                "分页查询模型列表: page={}, size={}, keyword={}, code={}, displayName={}, modelType={}, status={}, sourceType={}, sortField={}, sortOrder={}",
                page, size, logSafe(keyword), logSafe(code), logSafe(displayName), logSafe(modelType),
                logSafe(status), logSafe(sourceType), logSafe(sortField), logSafe(sortOrder)
        );

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
        long total = metaModelMapper.countByKeyword(
                searchKeyword, modelType, status, sourceType, currentOnly
        );

        // Get page data
        List<Model> models = metaModelMapper.searchByKeyword(
                searchKeyword, modelType, status, sourceType, sortField, sortOrder, currentOnly, offset, size
        );

        Map<Long, Integer> fieldCountsByModelId = loadUserFieldCountsByModelId(models);

        // Convert to DTOs
        List<MetaModelDTO> dtos = models.stream()
                .map(model -> convertToMetaModelDTO(
                        model,
                        model.getId() != null ? fieldCountsByModelId.getOrDefault(model.getId(), 0) : 0
                ))
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
        return publish(pid, versionNote, false, null);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"modelDefinitions", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public MetaModelDTO publish(String pid, String versionNote, Boolean impactAcknowledged, String acknowledgementNote) {
        logMetaOperation("publish", "pid=" + pid);

        Model model = findEntityByPid(pid);

        // Validate: must be DRAFT
        if (!model.isDraft()) {
            throw new MetaServiceException("Only DRAFT models can be published, current status: " + model.getStatus());
        }

        DDLPreviewResult ddlPreview = schemaManagementService.previewModelChanges(model.getCode());
        ModelPublishGovernanceDTO governance = buildPublishGovernance(model, ddlPreview);
        if (Boolean.TRUE.equals(governance.getRequiresAcknowledgement()) && !Boolean.TRUE.equals(impactAcknowledged)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "模型发布需要先确认规则中心影响: " + governanceImpactSummary(governance));
        }
        if (Boolean.TRUE.equals(governance.getRequiresAcknowledgement()) && Boolean.TRUE.equals(impactAcknowledged)) {
            recordModelPublishAcknowledgement(model, governance, acknowledgementNote);
        }

        // Skip table creation for VIEW models and models with skipTableCreation flag
        // (e.g., BPM system tables managed outside DSL schema management)
        if (model.isViewType() || model.isSkipTableCreation()) {
            log.info("Publishing model (no table creation): pid={}, code={}, reason={}",
                    logSafe(pid), logSafe(model.getCode()),
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
                                expandedFields.size(), logSafe(model.getCode()), logSafe(String.join(", ", expandedFields)));
                    }
                } catch (Exception e) {
                    // §P2 best-effort: MONEY/i18n field expansion is a derived view;
                    // a parse failure should not prevent the underlying model from
                    // loading. Caller falls back to the un-expanded field set.
                    log.warn("MONEY field expansion failed for model {} (non-blocking): {}",
                            logSafe(model.getCode()), logSafe(e.getMessage()), e);
                }
            }

            // Expand i18n-enabled fields (auto-create _en_us, _ja_jp, _ko_kr companion fields)
            try {
                List<String> i18nFields = i18nFieldExpander.expandI18nFields(model);
                if (!i18nFields.isEmpty()) {
                    log.info("i18n field expansion created {} field(s) for model {}: {}",
                            i18nFields.size(), logSafe(model.getCode()), logSafe(String.join(", ", i18nFields)));
                }
            } catch (Exception e) {
                // §P2 best-effort: see MONEY expansion above.
                log.warn("i18n field expansion failed for model {} (non-blocking): {}",
                        logSafe(model.getCode()), logSafe(e.getMessage()), e);
            }

            // Create table via SchemaManagementService
            log.info("Publishing model: pid={}, code={}", logSafe(pid), logSafe(model.getCode()));
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

        log.info("Model published successfully: pid={}, code={}", logSafe(pid), logSafe(model.getCode()));

        // Auto-create hierarchical permissions for the published model
        autoPermissionAssignmentService.autoAssignPermissions(model.getCode(), null);
        log.info("Hierarchical permissions created for model: {}", logSafe(model.getCode()));

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

        if (model.isSkipDefaultPages()) {
            log.info("Skipping all default page schemas for model: {}", logSafe(modelCode));
            return;
        }

        List<PageSpec> specs = new ArrayList<>();
        if (!model.isSkipListPageCreation()) {
            specs.add(new PageSpec("list", modelCode + "_list",
                "[{\"blockType\":\"toolbar\"},{\"blockType\":\"filters\"},{\"blockType\":\"table\"}]"));
        }
        if (!model.isSkipFormPageCreation()) {
            specs.add(new PageSpec("form", modelCode + "_form",
                "[{\"blockType\":\"form-section\"}]"));
        }
        if (!model.isSkipDetailPageCreation()) {
            specs.add(new PageSpec("detail", modelCode + "_detail",
                "[{\"blockType\":\"form-section\"},{\"blockType\":\"tabs\"}]"));
        }

        for (PageSpec spec : specs) {
            // Check existence by page_key (unique per tenant+namespace)
            com.auraboot.framework.meta.entity.PageSchema existing =
                pageSchemaMapper.selectAnyByPageKey(spec.pageKey());
            if (existing != null) {
                log.debug("Page schema already exists, skipping auto-create: pageKey={}", logSafe(spec.pageKey()));
                continue;
            }

            // Build title JSONB: {"en": "<ModelCode> <Kind>", "zh-CN": "<ModelCode> <Kind>"}
            String titleLabel = modelCode + " " + spec.kind();
            String titleJson = "{\"en\":\"" + titleLabel + "\",\"zh-CN\":\"" + titleLabel + "\"}";

            // Resolve env_id from MetaContext or fall back to tenant default
            Long envId = com.auraboot.framework.application.tenant.MetaContext.getCurrentEnvironmentId();
            if (envId == null) {
                envId = environmentService.findOrCreateDefaultId(tenantId);
            }

            int inserted = pageSchemaMapper.insertForPluginImport(
                UniqueIdGenerator.generate(),   // pid
                tenantId,                        // tenantId
                envId,                           // envId (env-layering #16)
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
                com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION, // schemaVersion (v4 flat blocks + grid)
                false,                           // isTemplate
                null,                            // templateCategory
                now,                             // publishedAt
                0,                               // sortWeight
                // Mark this row as an auto-generated stub so a subsequent
                // plugin import (importPage) can overwrite it unconditionally,
                // independent of the OVERWRITE_SAFE user-modified guard.
                "{\"auto_created\":true}",      // extension
                null                             // pluginPid
            );

            if (inserted > 0) {
                log.info("Auto-created default page schema: pageKey={}, kind={}, modelCode={}",
                    logSafe(spec.pageKey()), logSafe(spec.kind()), logSafe(modelCode));
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
            log.debug("Auto-marked field {} as searchable for model {}", logSafe(code), modelId);
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

        log.info("Model unpublished: pid={}, code={}", logSafe(pid), logSafe(model.getCode()));
        return convertToMetaModelDTO(model);
    }

    @Override
    public DDLPreviewResult previewPublishDDL(String pid) {
        logMetaOperation("previewPublishDDL", "pid=" + pid);

        Model model = findEntityByPid(pid);
        DDLPreviewResult result = schemaManagementService.previewModelChanges(model.getCode());
        result.setGovernance(buildPublishGovernance(model, result));
        return result;
    }

    @Override
    public ModelPublishReplayReportDTO replayPublishImpact(String pid, MetaModelPublishReplayRequest request) {
        logMetaOperation("replayPublishImpact", "pid=" + pid);

        Model model = findEntityByPid(pid);
        DDLPreviewResult ddlPreview = schemaManagementService.previewModelChanges(model.getCode());
        ModelPublishGovernanceDTO governance = buildPublishGovernance(model, ddlPreview);
        List<ModelPublishReplayResultDTO> results = governance.getReplayPlan() == null
                ? List.of()
                : governance.getReplayPlan().stream()
                        .map(step -> replayPublishStep(model, step, request))
                        .toList();

        return ModelPublishReplayReportDTO.builder()
                .modelCode(model.getCode())
                .draftVersion(model.getVersion())
                .latestPublishedVersion(governance.getLatestPublishedVersion())
                .generatedAt(Instant.now())
                .governance(governance)
                .totalCount(results.size())
                .automatedCount((int) results.stream().filter(r -> Boolean.TRUE.equals(r.getAutomated())).count())
                .executedCount((int) results.stream().filter(r -> Boolean.TRUE.equals(r.getExecuted())).count())
                .manualCount((int) results.stream().filter(r -> "MANUAL_REQUIRED".equals(r.getStatus())).count())
                .failedCount((int) results.stream().filter(r -> "FAILED".equals(r.getStatus())).count())
                .needsInputCount((int) results.stream().filter(r -> "NEEDS_SAMPLE_CONTEXT".equals(r.getStatus())).count())
                .results(results)
                .build();
    }

    private ModelPublishGovernanceDTO buildPublishGovernance(Model model, DDLPreviewResult ddlPreview) {
        List<String> ddlStatements = ddlPreview != null && ddlPreview.getDdlStatements() != null
                ? ddlPreview.getDdlStatements()
                : List.of();
        List<String> schemaChangeKinds = classifySchemaChangeKinds(ddlStatements);
        boolean schemaChangeDetected = !schemaChangeKinds.isEmpty();
        List<String> warnings = new ArrayList<>();
        List<DecisionFieldImpactDTO> fieldImpacts = List.of();
        if (decisionImpactService == null) {
            warnings.add("Rule Center impact service is unavailable; publish governance can only report DDL risk.");
        } else {
            fieldImpacts = loadRuleCenterFieldImpacts(model, warnings);
        }

        boolean requiresAcknowledgement = schemaChangeDetected && fieldImpacts.stream()
                .anyMatch(impact -> impact.getRisk() != null && Boolean.TRUE.equals(impact.getRisk().getBlocking()));
        List<ModelPublishReplayStepDTO> replayPlan = buildReplayPlan(fieldImpacts);

        return ModelPublishGovernanceDTO.builder()
                .modelCode(model.getCode())
                .draftVersion(model.getVersion())
                .latestPublishedVersion(latestPublishedVersion(model))
                .allowed(!requiresAcknowledgement)
                .blocked(requiresAcknowledgement)
                .requiresAcknowledgement(requiresAcknowledgement)
                .schemaChangeDetected(schemaChangeDetected)
                .schemaChangeKinds(schemaChangeKinds)
                .fieldImpacts(fieldImpacts)
                .replayPlan(replayPlan)
                .migrationPlan(buildMigrationPlan(schemaChangeKinds, fieldImpacts))
                .historicalVersionPolicy(buildHistoricalVersionPolicy(model))
                .warnings(warnings)
                .build();
    }

    private List<DecisionFieldImpactDTO> loadRuleCenterFieldImpacts(Model model, List<String> warnings) {
        if (model == null || model.getId() == null) {
            return List.of();
        }
        List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(model.getId());
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fieldIds.isEmpty()) {
            return List.of();
        }
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }

        List<DecisionFieldImpactDTO> impacts = new ArrayList<>();
        for (Field field : fields) {
            if (field == null || SystemFieldConstants.isSystemField(field.getCode())) {
                continue;
            }
            String publishFieldRef = model.getCode() + "." + field.getCode();
            try {
                DecisionFieldImpactDTO impact = loadFirstFieldImpact(candidatePublishFieldRefs(model, field));
                if (impact != null && impact.getReferences() != null && !impact.getReferences().isEmpty()) {
                    impact.setFieldRef(publishFieldRef);
                    Map<String, Object> fieldGovernance = fieldGovernanceReplayMetadata(field);
                    if (!fieldGovernance.isEmpty()) {
                        impact.setReferences(impact.getReferences().stream()
                                .map(ref -> withMergedReplayMetadata(ref, fieldGovernance))
                                .toList());
                    }
                    impacts.add(impact);
                }
            } catch (Exception e) {
                warnings.add("Failed to read Rule Center usage for " + publishFieldRef + ": " + e.getMessage());
            }
        }
        return impacts;
    }

    private DecisionFieldImpactDTO loadFirstFieldImpact(List<String> fieldRefs) {
        if (fieldRefs == null || fieldRefs.isEmpty()) {
            return null;
        }
        for (String fieldRef : fieldRefs) {
            DecisionFieldImpactDTO impact = decisionImpactService.getFieldImpact(fieldRef);
            if (impact != null && impact.getReferences() != null && !impact.getReferences().isEmpty()) {
                return impact;
            }
        }
        return null;
    }

    private List<String> candidatePublishFieldRefs(Model model, Field field) {
        if (model == null || field == null || !StringUtils.hasText(field.getCode())) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (StringUtils.hasText(model.getCode())) {
            refs.add(model.getCode() + "." + field.getCode());
        }
        refs.add("record." + recordDataPath(field.getCode()));
        refs.add("record." + field.getCode());
        return List.copyOf(refs);
    }

    private String recordDataPath(String fieldCode) {
        if (!StringUtils.hasText(fieldCode)) {
            return fieldCode;
        }
        return fieldCode.startsWith("data.") ? fieldCode : "data." + fieldCode;
    }

    private List<String> classifySchemaChangeKinds(List<String> ddlStatements) {
        if (ddlStatements == null || ddlStatements.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        for (String ddl : ddlStatements) {
            String normalized = ddl == null ? "" : ddl.toUpperCase(Locale.ROOT);
            if (normalized.contains("CREATE TABLE")) {
                kinds.add("CREATE_TABLE");
            } else if (normalized.contains("ADD COLUMN")) {
                kinds.add("ADD_COLUMN");
            } else if (normalized.contains("DROP COLUMN")) {
                kinds.add("DROP_COLUMN");
            } else if (normalized.contains("ALTER COLUMN") && normalized.contains(" TYPE ")) {
                kinds.add("ALTER_COLUMN_TYPE");
            } else if (normalized.contains("ALTER COLUMN")
                    && (normalized.contains("SET NOT NULL") || normalized.contains("DROP NOT NULL"))) {
                kinds.add("NULLABILITY");
            } else if (normalized.contains("CREATE INDEX") || normalized.contains("CREATE UNIQUE INDEX")) {
                kinds.add("INDEX");
            } else if (StringUtils.hasText(ddl)) {
                kinds.add("OTHER_DDL");
            }
        }
        return List.copyOf(kinds);
    }

    private String buildMigrationPlan(List<String> schemaChangeKinds, List<DecisionFieldImpactDTO> fieldImpacts) {
        if (schemaChangeKinds == null || schemaChangeKinds.isEmpty()) {
            return "No physical schema migration is required. Rebuild the Rule Center usage index if field metadata changed without DDL.";
        }
        List<String> steps = new ArrayList<>();
        if (schemaChangeKinds.contains("CREATE_TABLE")) {
            steps.add("Create the physical table and generated indexes before enabling runtime writes.");
        }
        if (schemaChangeKinds.contains("ADD_COLUMN")) {
            steps.add("Backfill new columns or define defaults before routing rules to the new field.");
        }
        if (schemaChangeKinds.contains("ALTER_COLUMN_TYPE")) {
            steps.add("Validate data casts and replay affected rules against representative records before promotion.");
        }
        if (schemaChangeKinds.contains("DROP_COLUMN")) {
            steps.add("Retire or migrate every affected rule consumer before removing the column.");
        }
        if (schemaChangeKinds.contains("NULLABILITY")) {
            steps.add("Check existing rows against required/nullability changes before publish.");
        }
        if (fieldImpacts != null && !fieldImpacts.isEmpty()) {
            steps.add("Confirm Rule Center blast radius and republish or replay affected BPM, SLA, Automation, EventPolicy and decision versions.");
        }
        if (steps.isEmpty()) {
            steps.add("Review generated DDL and run a post-publish schema sync smoke.");
        }
        return String.join(" ", steps);
    }

    private List<ModelPublishReplayStepDTO> buildReplayPlan(List<DecisionFieldImpactDTO> fieldImpacts) {
        if (fieldImpacts == null || fieldImpacts.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ModelPublishReplayStepDTO> steps = new LinkedHashMap<>();
        for (DecisionFieldImpactDTO impact : fieldImpacts) {
            if (impact == null || impact.getReferences() == null || impact.getReferences().isEmpty()) {
                continue;
            }
            boolean required = impact.getRisk() != null && Boolean.TRUE.equals(impact.getRisk().getBlocking());
            for (DecisionImpactRefDTO ref : impact.getReferences()) {
                if (ref == null || !StringUtils.hasText(ref.getSourceType())) {
                    continue;
                }
                String consumerType = normalizeReplayConsumerType(ref.getSourceType());
                String key = String.join("|",
                        nullToBlank(consumerType),
                        nullToBlank(ref.getSourcePid()),
                        nullToBlank(ref.getSourceCode()),
                        nullToBlank(ref.getTargetPath()),
                        nullToBlank(ref.getBinding()));
                steps.putIfAbsent(key, ModelPublishReplayStepDTO.builder()
                        .consumerType(consumerType)
                        .consumerLabel(replayConsumerLabel(consumerType))
                        .sourceCode(ref.getSourceCode())
                        .sourceName(ref.getSourceName())
                        .sourceVersion(ref.getSourceVersion())
                        .sourcePid(ref.getSourcePid())
                        .fieldRef(impact.getFieldRef())
                        .targetPath(StringUtils.hasText(ref.getTargetPath()) ? ref.getTargetPath() : ref.getTargetCode())
                        .binding(ref.getBinding())
                        .recommendedAction(replayRecommendedAction(consumerType))
                        .required(required)
                        .metadata(replayStepMetadata(impact, ref))
                        .build());
            }
        }
        return List.copyOf(steps.values());
    }

    private Map<String, Object> replayStepMetadata(DecisionFieldImpactDTO impact, DecisionImpactRefDTO ref) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (ref != null && ref.getMetadata() != null) {
            metadata.putAll(ref.getMetadata());
        }
        if (impact != null && impact.getRisk() != null && StringUtils.hasText(impact.getRisk().getSummary())) {
            metadata.putIfAbsent("impactRiskSummary", impact.getRisk().getSummary());
        }
        return metadata.isEmpty() ? Map.of() : metadata;
    }

    private DecisionImpactRefDTO withMergedReplayMetadata(
            DecisionImpactRefDTO source,
            Map<String, Object> additionalMetadata) {
        if (source == null || additionalMetadata == null || additionalMetadata.isEmpty()) {
            return source;
        }
        DecisionImpactRefDTO copy = new DecisionImpactRefDTO();
        copy.setSourceType(source.getSourceType());
        copy.setSourceCode(source.getSourceCode());
        copy.setSourceName(source.getSourceName());
        copy.setSourceVersion(source.getSourceVersion());
        copy.setSourcePid(source.getSourcePid());
        copy.setTargetType(source.getTargetType());
        copy.setTargetCode(source.getTargetCode());
        copy.setTargetPath(source.getTargetPath());
        copy.setBinding(source.getBinding());
        Map<String, Object> metadata = new LinkedHashMap<>(additionalMetadata);
        if (source.getMetadata() != null) {
            metadata.putAll(source.getMetadata());
        }
        copy.setMetadata(metadata);
        return copy;
    }

    private Map<String, Object> fieldGovernanceReplayMetadata(Field field) {
        if (field == null) {
            return Map.of();
        }
        List<Map<String, Object>> sources = fieldGovernanceMetadataSources(field);
        boolean masked = Boolean.TRUE.equals(firstFieldGovernanceBoolean(
                sources, "masked", "mask", "masking", "sensitive", "pii"));
        boolean permissionChange = Boolean.TRUE.equals(firstFieldGovernanceBoolean(
                sources, "fieldPermissionChange", "permissionChange", "changePermission", "permissionChanged"))
                || hasPermissionRule(field)
                || hasFeaturePermission(field);
        String permission = firstFieldGovernanceString(
                sources, "permission", "permissionCode", "readPermission", "viewPermission");

        FieldRuleSchemaBean.PermissionRule permissionRule = field.getRuleSchema() != null
                ? field.getRuleSchema().getPermissionRule()
                : null;
        if (permissionRule != null && permissionRule.getFieldSecurity() != null
                && Boolean.TRUE.equals(permissionRule.getFieldSecurity().getMaskSensitive())) {
            masked = true;
        }
        if (StringUtils.hasText(permission)) {
            permissionChange = true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (masked) {
            metadata.put("fieldMasked", true);
        }
        if (permissionChange) {
            metadata.put("fieldPermissionChange", true);
        }
        if (StringUtils.hasText(permission)) {
            metadata.put("fieldPermission", permission);
        }
        if (masked || permissionChange) {
            metadata.put("fieldRiskLevel", permissionChange ? "FIELD_PERMISSION_CHANGE" : "FIELD_MASKED");
            metadata.put("fieldRiskSummary", fieldRiskSummary(masked, permissionChange));
            metadata.put("requiresLowPermissionSample", true);
        }
        return metadata;
    }

    private List<Map<String, Object>> fieldGovernanceMetadataSources(Field field) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (field == null) {
            return sources;
        }
        Map<String, Object> extension = flattenFieldExtension(field.getExtension());
        if (!extension.isEmpty()) {
            sources.add(extension);
        }
        if (field.getRuleSchema() != null && field.getRuleSchema().getExtensions() != null) {
            sources.add(field.getRuleSchema().getExtensions());
        }
        if (field.getFeature() != null && field.getFeature().getExtensions() != null) {
            sources.add(field.getFeature().getExtensions());
        }
        if (field.getUiSchema() != null && field.getUiSchema().getExtensions() != null) {
            sources.add(field.getUiSchema().getExtensions());
        }
        return sources;
    }

    private boolean hasPermissionRule(Field field) {
        return field != null && field.getRuleSchema() != null && field.getRuleSchema().getPermissionRule() != null;
    }

    private boolean hasFeaturePermission(Field field) {
        return field != null && field.getFeature() != null && field.getFeature().getPermission() != null;
    }

    private String fieldRiskSummary(boolean masked, boolean permissionChange) {
        if (masked && permissionChange) {
            return "MASKED_PERMISSION_CHANGE";
        }
        if (permissionChange) {
            return "PERMISSION_CHANGE";
        }
        return "MASKED_FIELD";
    }

    private Boolean firstFieldGovernanceBoolean(List<Map<String, Object>> sources, String... keys) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        for (Map<String, Object> source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                Object value = source.get(key);
                if (value instanceof Boolean booleanValue) {
                    return booleanValue;
                }
                if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
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

    private String firstFieldGovernanceString(List<Map<String, Object>> sources, String... keys) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        for (Map<String, Object> source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                Object value = source.get(key);
                if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
                    return stringValue;
                }
            }
        }
        return null;
    }

    private ModelPublishReplayResultDTO replayPublishStep(
            Model model,
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if ("EVENT_POLICY".equals(nullToBlank(step.getConsumerType()))) {
            return replayEventPolicyStep(model, step, request);
        }
        if ("AUTOMATION".equals(nullToBlank(step.getConsumerType()))) {
            return replayAutomationStep(step, request);
        }
        if ("BPM_PROCESS".equals(nullToBlank(step.getConsumerType()))) {
            return replayBpmStep(step, request);
        }
        if ("SLA_RULE".equals(nullToBlank(step.getConsumerType()))) {
            return replaySlaStep(step, request);
        }
        if ("PERMISSION_POLICY".equals(nullToBlank(step.getConsumerType()))) {
            return replayPermissionStep(step, request);
        }
        if (!"DECISION_VERSION".equals(nullToBlank(step.getConsumerType()))) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("MANUAL_REQUIRED")
                    .automated(false)
                    .executed(false)
                    .message(step.getRecommendedAction())
                    .errors(List.of())
                    .outputs(Map.of())
                    .build();
        }
        return replayDecisionStep(model, step, request);
    }

    private ModelPublishReplayResultDTO replayBpmStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (processDeploymentService == null || bpmRuleBindingRuntimeService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("BPM_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("BPM replay service is unavailable in this runtime.")
                    .errors(List.of("BPM_REPLAY_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        if (!StringUtils.hasText(step.getSourcePid())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("BPM replay requires sourcePid.")
                    .errors(List.of("MISSING_BPM_PROCESS_PID"))
                    .outputs(Map.of())
                    .build();
        }

        BpmProcessDefinition process = processDeploymentService.getByPid(step.getSourcePid());
        if (process == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("BPM process was not found for replay.")
                    .errors(List.of("BPM_PROCESS_NOT_FOUND"))
                    .outputs(Map.of("processPid", step.getSourcePid()))
                    .build();
        }

        BpmReplayBinding replayBinding = resolveBpmReplayBinding(process, step);
        if (replayBinding == null || replayBinding.binding() == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("MANUAL_REQUIRED")
                    .automated(false)
                    .executed(false)
                    .message("BPM replay requires node ruleBinding or edge conditionSpec metadata. Rebuild the Rule Center usage index or open the BPMN designer for manual review.")
                    .errors(List.of())
                    .outputs(bpmReplayBaseOutputs(process, step, replayBinding, null, null, null))
                    .build();
        }

        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("BPM replay is available. Pass executeAutomated=true with sampleContext.record data; sampleContext.bpm.processInstanceId is optional for execution-log persistence.")
                    .errors(List.of())
                    .outputs(bpmReplayBaseOutputs(process, step, replayBinding, null, null, null))
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return bpmNeedsSampleContext(process, step, replayBinding,
                    "BPM replay requires representative sampleContext.");
        }

        Map<String, Object> variables = bpmReplayVariables(request.getSampleContext());
        String processInstanceId = sampleBpmProcessInstanceId(request.getSampleContext());
        try {
            BpmReplayExecution execution = executeBpmReplay(replayBinding, process, processInstanceId, variables);
            RuleEvaluationTrace trace = execution.trace();
            boolean failed = bpmTraceFailed(trace) || execution.failClosed();
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(failed ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!failed)
                    .message(bpmReplayMessage(replayBinding, trace, execution.failClosed()))
                    .traceId(trace != null ? trace.traceId() : null)
                    .matched(trace != null && trace.matched())
                    .outputs(bpmReplayBaseOutputs(process, step, replayBinding, variables, trace, execution.assignment()))
                    .errors(bpmReplayErrors(trace, execution.failClosed()))
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("BPM replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(bpmReplayBaseOutputs(process, step, replayBinding, variables, null, null))
                    .build();
        }
    }

    private String bpmReplayMessage(
            BpmReplayBinding replayBinding,
            RuleEvaluationTrace trace,
            boolean failClosed) {
        if (trace == null) {
            return "BPM replay returned no rule trace.";
        }
        if (failClosed) {
            return "BPM rule binding failed closed after decision evaluation error.";
        }
        return "BPM replay evaluated " + replayBinding.surfaceLabel() + " with matched=" + trace.matched() + ".";
    }

    private ModelPublishReplayResultDTO bpmNeedsSampleContext(
            BpmProcessDefinition process,
            ModelPublishReplayStepDTO step,
            BpmReplayBinding replayBinding,
            String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(bpmReplayBaseOutputs(process, step, replayBinding, null, null, null))
                .build();
    }

    private BpmReplayExecution executeBpmReplay(
            BpmReplayBinding replayBinding,
            BpmProcessDefinition process,
            String processInstanceId,
            Map<String, Object> variables) {
        RuleConsumerBinding binding = replayBinding.binding();
        boolean assignmentNode = "userTask".equalsIgnoreCase(replayBinding.nodeType());
        if (assignmentNode && binding.bindingKind() == RuleBindingKind.DECISION_REF) {
            BpmRuleBindingRuntimeService.TaskAssignmentResult assignment =
                    bpmRuleBindingRuntimeService.resolveTaskAssignment(
                            binding,
                            process.getProcessKey(),
                            replayBinding.nodeId(),
                            processInstanceId,
                            variables);
            return new BpmReplayExecution(assignment.trace(), assignment.failClosed(), assignment);
        }
        Optional<RuleEvaluationTrace> trace = StringUtils.hasText(processInstanceId)
                ? bpmRuleBindingRuntimeService.evaluateAndApply(
                        binding,
                        process.getProcessKey(),
                        replayBinding.nodeId(),
                        processInstanceId,
                        variables)
                : bpmRuleBindingRuntimeService.evaluate(
                        binding,
                        process.getProcessKey(),
                        replayBinding.nodeId(),
                        null,
                        variables);
        return new BpmReplayExecution(trace.orElse(null), false, null);
    }

    private boolean bpmTraceFailed(RuleEvaluationTrace trace) {
        if (trace == null) {
            return true;
        }
        if (trace.decisionStatus() == DecisionStatus.ERROR) {
            return true;
        }
        return trace.errorCode() != null && !trace.errorCode().isBlank();
    }

    private List<String> bpmReplayErrors(RuleEvaluationTrace trace, boolean failClosed) {
        List<String> errors = new ArrayList<>();
        if (trace == null) {
            errors.add("BPM_REPLAY_RETURNED_NO_TRACE");
        } else if (trace.errors() != null) {
            errors.addAll(trace.errors());
        }
        if (failClosed) {
            errors.add("BPM_RULE_BINDING_FAIL_CLOSED");
        }
        return List.copyOf(errors);
    }

    private Map<String, Object> bpmReplayVariables(Map<String, Map<String, Object>> sampleContext) {
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, Object> bpm = sampleContext == null ? null : sampleContext.get("bpm");
        if (bpm != null) {
            variables.putAll(bpm);
        }
        Map<String, Object> meta = sampleContext == null ? null : sampleContext.get("meta");
        if (meta != null && !meta.isEmpty()) {
            variables.put("meta", new LinkedHashMap<>(meta));
        }
        Map<String, Object> recordData = sampleRecordData(sampleContext);
        Map<String, Object> record = new LinkedHashMap<>(recordData);
        String recordPid = sampleRecordPid(sampleContext);
        if (StringUtils.hasText(recordPid)) {
            record.put("pid", recordPid);
            record.put("recordPid", recordPid);
        }
        record.put("data", new LinkedHashMap<>(recordData));
        variables.put("record", record);
        variables.putAll(recordData);
        return variables;
    }

    private BpmReplayBinding resolveBpmReplayBinding(
            BpmProcessDefinition process,
            ModelPublishReplayStepDTO step) {
        JsonNode designer = bpmDesignerJson(process);
        if (designer == null || designer.isMissingNode() || designer.isNull()) {
            return null;
        }
        Map<String, Object> metadata = step.getMetadata() == null ? Map.of() : step.getMetadata();
        String nodeId = metadataText(metadata, "nodeId");
        if (StringUtils.hasText(nodeId)) {
            JsonNode node = findDesignerItem(designer.path("nodes"), nodeId);
            JsonNode ruleBinding = node.path("data").path("config").path("ruleBinding");
            if (ruleBinding.isObject() && !ruleBinding.isEmpty()) {
                return new BpmReplayBinding(
                        bpmRuleBinding(ruleBinding, process.getProcessKey(), nodeId),
                        nodeId,
                        text(node.path("type")),
                        null,
                        null,
                        null,
                        "node ruleBinding");
            }
        }
        String edgeId = metadataText(metadata, "edgeId");
        if (StringUtils.hasText(edgeId)) {
            JsonNode edge = findDesignerItem(designer.path("edges"), edgeId);
            JsonNode conditionSpec = edge.path("data").path("conditionSpec");
            if (conditionSpec.isObject() && !conditionSpec.isEmpty()) {
                String source = text(edge.path("source"));
                return new BpmReplayBinding(
                        new RuleConsumerBinding(
                                "BPM",
                                process.getProcessKey(),
                                StringUtils.hasText(source) ? source : edgeId,
                                RuleBindingKind.CONDITION,
                                objectMapper.convertValue(conditionSpec, ConditionSpec.class),
                                null,
                                true),
                        StringUtils.hasText(source) ? source : edgeId,
                        "sequenceFlow",
                        edgeId,
                        source,
                        text(edge.path("target")),
                        "edge conditionSpec");
            }
        }
        return null;
    }

    private RuleConsumerBinding bpmRuleBinding(JsonNode node, String processKey, String nodeId) {
        RuleConsumerBinding parsed = objectMapper.convertValue(node, RuleConsumerBinding.class);
        return new RuleConsumerBinding(
                StringUtils.hasText(parsed.consumerType()) ? parsed.consumerType() : "BPM",
                StringUtils.hasText(parsed.consumerCode()) ? parsed.consumerCode() : processKey,
                StringUtils.hasText(parsed.consumerNodeId()) ? parsed.consumerNodeId() : nodeId,
                parsed.bindingKind(),
                parsed.conditionSpec(),
                parsed.decisionBinding(),
                parsed.enabled(),
                parsed.conditionFragmentRefs());
    }

    private JsonNode bpmDesignerJson(BpmProcessDefinition process) {
        if (process == null || process.getExtension() == null) {
            return null;
        }
        Object value = process.getExtension().get("designerJson");
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof JsonNode node) {
                return node;
            }
            if (value instanceof String text) {
                return objectMapper.readTree(text);
            }
            return objectMapper.valueToTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed BPM designerJson for replay: " + e.getMessage(), e);
        }
    }

    private JsonNode findDesignerItem(JsonNode items, String id) {
        if (items == null || !items.isArray() || !StringUtils.hasText(id)) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        for (JsonNode item : items) {
            if (id.equals(text(item.path("id")))) {
                return item;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private Map<String, Object> bpmReplayBaseOutputs(
            BpmProcessDefinition process,
            ModelPublishReplayStepDTO step,
            BpmReplayBinding replayBinding,
            Map<String, Object> variables,
            RuleEvaluationTrace trace,
            BpmRuleBindingRuntimeService.TaskAssignmentResult assignment) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (process != null) {
            outputs.put("processPid", process.getPid());
            outputs.put("processKey", process.getProcessKey());
            outputs.put("processName", process.getProcessName());
            outputs.put("processVersion", process.getVersion());
            outputs.put("processStatus", process.getStatus());
        } else if (step != null) {
            outputs.put("processPid", step.getSourcePid());
            outputs.put("processKey", step.getSourceCode());
        }
        if (replayBinding != null) {
            outputs.put("nodeId", replayBinding.nodeId());
            outputs.put("nodeType", replayBinding.nodeType());
            outputs.put("edgeId", replayBinding.edgeId());
            outputs.put("edgeSource", replayBinding.edgeSource());
            outputs.put("edgeTarget", replayBinding.edgeTarget());
            outputs.put("bindingSurface", replayBinding.surfaceLabel());
            RuleConsumerBinding binding = replayBinding.binding();
            if (binding != null) {
                outputs.put("bindingKind", binding.bindingKind() != null ? binding.bindingKind().name() : null);
                outputs.put("decisionCode", binding.decisionBinding() != null
                        ? binding.decisionBinding().decisionCode()
                        : null);
            }
        }
        if (trace != null) {
            outputs.put("traceId", trace.traceId());
            outputs.put("matched", trace.matched());
            outputs.put("decisionStatus", trace.decisionStatus() != null ? trace.decisionStatus().name() : null);
            outputs.put("conditionResult", trace.conditionResult() != null ? trace.conditionResult().name() : null);
            outputs.put("fallbackApplied", trace.fallbackApplied());
            outputs.put("durationMs", trace.durationMs());
            outputs.put("errorCode", trace.errorCode());
            outputs.put("inputs", trace.inputSnapshot());
            outputs.put("outputs", trace.outputSnapshot());
            outputs.put("fieldRefs", trace.fieldRefs());
            outputs.put("decisionRefs", trace.decisionRefs());
        }
        if (assignment != null) {
            outputs.put("candidateUserIds", assignment.userIds());
            outputs.put("candidateGroupIds", assignment.groupIds());
            outputs.put("failClosed", assignment.failClosed());
        }
        Object processInstanceId = variables == null ? null : variables.get("processInstanceId");
        if (processInstanceId instanceof String value && StringUtils.hasText(value)) {
            outputs.put("processInstanceId", value);
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private record BpmReplayBinding(
            RuleConsumerBinding binding,
            String nodeId,
            String nodeType,
            String edgeId,
            String edgeSource,
            String edgeTarget,
            String surfaceLabel
    ) {}

    private record BpmReplayExecution(
            RuleEvaluationTrace trace,
            boolean failClosed,
            BpmRuleBindingRuntimeService.TaskAssignmentResult assignment
    ) {}

    private ModelPublishReplayResultDTO replayPermissionStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (permissionEvaluator == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("PERMISSION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("Permission replay service is unavailable in this runtime.")
                    .errors(List.of("PERMISSION_REPLAY_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }

        Map<String, Map<String, Object>> sampleContext = request != null ? request.getSampleContext() : null;
        Long memberId = samplePermissionMemberId(sampleContext);
        String permissionCode = permissionReplayPermissionCode(step, sampleContext);
        String resource = permissionReplayResource(step, sampleContext, permissionCode);
        String action = permissionReplayAction(step, sampleContext, permissionCode);
        String recordPid = sampleRecordPid(sampleContext);
        Map<String, Object> record = permissionReplayRecord(sampleContext, recordPid);

        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("Permission replay is available. Pass executeAutomated=true with sampleContext.permission.memberId and sampleContext.record data.")
                    .errors(List.of())
                    .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                    .build();
        }
        if (sampleContext == null || sampleContext.isEmpty()) {
            return permissionNeedsSampleContext(step, permissionCode, resource, action, null, null,
                    "Permission replay requires representative sampleContext.");
        }
        if (memberId == null) {
            return permissionNeedsSampleContext(step, permissionCode, resource, action, null, recordPid,
                    "Permission replay requires sampleContext.permission.memberId or sampleContext.actor.memberId.");
        }
        if (!StringUtils.hasText(resource) || !StringUtils.hasText(action)) {
            return permissionNeedsSampleContext(step, permissionCode, resource, action, memberId, recordPid,
                    "Permission replay requires resource/action metadata or sampleContext.permission.resource/action.");
        }

        try {
            PermissionResult result = permissionEvaluator.canOperate(memberId, resource, action, record);
            if (result == null) {
                return ModelPublishReplayResultDTO.builder()
                        .step(step)
                        .status("FAILED")
                        .automated(true)
                        .executed(false)
                        .message("Permission replay returned no result.")
                        .errors(List.of("PERMISSION_REPLAY_RETURNED_NO_RESULT"))
                        .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                        .build();
            }
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("EXECUTED")
                    .automated(true)
                    .executed(true)
                    .message("Permission replay executed with decision " + (result.granted() ? "ALLOW" : "DENY") + ".")
                    .matched(result.granted())
                    .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, result))
                    .errors(List.of())
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Permission replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                    .build();
        }
    }

    private ModelPublishReplayResultDTO permissionNeedsSampleContext(
            ModelPublishReplayStepDTO step,
            String permissionCode,
            String resource,
            String action,
            Long memberId,
            String recordPid,
            String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(permissionReplayOutputs(step, permissionCode, resource, action, memberId, recordPid, null))
                .build();
    }

    private ModelPublishReplayResultDTO replaySlaStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (slaConfigService == null || slaActivationListener == null || slaRecordService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("SLA_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("SLA replay service is unavailable in this runtime.")
                    .errors(List.of("SLA_REPLAY_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        if (!StringUtils.hasText(step.getSourcePid())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("SLA replay requires sourcePid.")
                    .errors(List.of("MISSING_SLA_CONFIG_PID"))
                    .outputs(Map.of())
                    .build();
        }

        SlaConfigEntity config = slaConfigService.getByPid(step.getSourcePid());
        if (config == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("SLA config was not found for replay.")
                    .errors(List.of("SLA_CONFIG_NOT_FOUND"))
                    .outputs(Map.of("slaConfigPid", step.getSourcePid()))
                    .build();
        }

        String targetType = nullToBlank(config.getTargetType()).trim().toUpperCase(Locale.ROOT);
        if ("NODE".equals(targetType)) {
            return replaySlaNodeStep(step, config, request);
        }
        if (!"RECORD".equals(targetType)) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("MANUAL_REQUIRED")
                    .automated(false)
                    .executed(false)
                    .message("SLA replay currently supports RECORD-level and BPM NODE activation.")
                    .errors(List.of())
                    .outputs(slaReplayOutputs(config, null, null))
                    .build();
        }

        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("SLA RECORD replay is available. Pass executeAutomated=true with sampleContext.record.pid and record.data.")
                    .errors(List.of())
                    .outputs(slaReplayOutputs(config, null, null))
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return slaNeedsSampleContext(step, config, "SLA RECORD replay requires representative sampleContext.");
        }

        String recordPid = sampleRecordPid(request.getSampleContext());
        if (!StringUtils.hasText(recordPid)) {
            return slaNeedsSampleContext(step, config,
                    "SLA RECORD replay requires sampleContext.record.pid or sampleContext.record.recordPid.");
        }
        if (!StringUtils.hasText(config.getTargetKey())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("SLA RECORD replay requires targetKey model code on the SLA config.")
                    .errors(List.of("MISSING_SLA_TARGET_KEY"))
                    .outputs(slaReplayOutputs(config, recordPid, null))
                    .build();
        }

        Map<String, Object> recordData = sampleRecordData(request.getSampleContext());
        try {
            slaActivationListener.onRecordCreate(config.getTargetKey(), recordPid, recordData);
            SlaRecordEntity record = latestSlaRecord(recordPid, config);
            boolean created = record != null;
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(created ? "EXECUTED" : "FAILED")
                    .automated(true)
                    .executed(created)
                    .message(created
                            ? "SLA RECORD replay activated an SLA record."
                            : "SLA RECORD replay did not create an SLA record.")
                    .traceId(record != null ? record.getPid() : null)
                    .matched(created)
                    .outputs(slaReplayOutputs(config, recordPid, record))
                    .errors(created ? List.of() : List.of("SLA_RECORD_NOT_CREATED"))
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("SLA RECORD replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(slaReplayOutputs(config, recordPid, null))
                    .build();
        }
    }

    private ModelPublishReplayResultDTO replaySlaNodeStep(
            ModelPublishReplayStepDTO step,
            SlaConfigEntity config,
            MetaModelPublishReplayRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("SLA NODE replay is available. Pass executeAutomated=true with sampleContext.bpm.processInstanceId, tenantId and taskId.")
                    .errors(List.of())
                    .outputs(slaNodeReplayOutputs(config, null, null, null))
                    .build();
        }
        Map<String, Map<String, Object>> sampleContext = request.getSampleContext();
        if (sampleContext == null || sampleContext.isEmpty()) {
            return slaNodeNeedsSampleContext(step, config, null, null,
                    "SLA NODE replay requires representative sampleContext.");
        }
        String activityId = config.getTargetKey();
        if (!StringUtils.hasText(activityId)) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("SLA NODE replay requires targetKey activity id on the SLA config.")
                    .errors(List.of("MISSING_SLA_TARGET_KEY"))
                    .outputs(slaNodeReplayOutputs(config, null, null, null))
                    .build();
        }
        String processInstanceId = sampleBpmProcessInstanceId(sampleContext);
        String taskId = sampleBpmTaskId(sampleContext);
        if (!StringUtils.hasText(processInstanceId)) {
            return slaNodeNeedsSampleContext(step, config, null, taskId,
                    "SLA NODE replay requires sampleContext.bpm.processInstanceId or sampleContext.bpm.instanceId.");
        }
        Long tenantId = sampleBpmTenantId(sampleContext);
        if (tenantId == null || tenantId == 0L) {
            return slaNodeNeedsSampleContext(step, config, processInstanceId, taskId,
                    "SLA NODE replay requires sampleContext.bpm.tenantId or an active MetaContext tenant.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> bpm = sampleContext.get("bpm");
        if (bpm != null) {
            payload.putAll(bpm);
        }
        if (StringUtils.hasText(taskId)) {
            payload.put("taskInstanceId", taskId);
        }
        Map<String, Object> recordData = sampleRecordData(sampleContext);
        if (!recordData.isEmpty()) {
            payload.put("record", Map.of("data", recordData));
        }
        String processKey = sampleBpmProcessKey(sampleContext);
        try {
            BpmEvent event = BpmEvent.of(
                    tenantId,
                    "task_assigned",
                    "bpm",
                    processKey,
                    processInstanceId,
                    activityId,
                    payload);
            slaActivationListener.onBpmEvent(event);
            SlaRecordEntity record = latestSlaRecord(processInstanceId, config);
            boolean created = record != null;
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(created ? "EXECUTED" : "FAILED")
                    .automated(true)
                    .executed(created)
                    .message(created
                            ? "SLA NODE replay activated an SLA record from a BPM task assignment sample."
                            : "SLA NODE replay did not create an SLA record from the BPM task assignment sample.")
                    .traceId(record != null ? record.getPid() : null)
                    .matched(created)
                    .outputs(slaNodeReplayOutputs(config, processInstanceId, taskId, record))
                    .errors(created ? List.of() : List.of("SLA_NODE_RECORD_NOT_CREATED"))
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("SLA NODE replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(slaNodeReplayOutputs(config, processInstanceId, taskId, null))
                    .build();
        }
    }

    private ModelPublishReplayResultDTO slaNodeNeedsSampleContext(
            ModelPublishReplayStepDTO step,
            SlaConfigEntity config,
            String processInstanceId,
            String taskId,
            String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(slaNodeReplayOutputs(config, processInstanceId, taskId, null))
                .build();
    }

    private ModelPublishReplayResultDTO slaNeedsSampleContext(
            ModelPublishReplayStepDTO step,
            SlaConfigEntity config,
            String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(slaReplayOutputs(config, null, null))
                .build();
    }

    private Map<String, Object> sampleRecordData(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> record = sampleContext.get("record");
        if (record != null && record.get("data") instanceof Map<?, ?> data) {
            return data.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String)
                    .collect(Collectors.toMap(
                            entry -> (String) entry.getKey(),
                            Map.Entry::getValue,
                            (left, right) -> right,
                            LinkedHashMap::new));
        }
        return record == null ? Map.of() : record.entrySet().stream()
                .filter(entry -> !"pid".equals(entry.getKey())
                        && !"recordPid".equals(entry.getKey())
                        && !"publicId".equals(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private SlaRecordEntity latestSlaRecord(String recordPid, SlaConfigEntity config) {
        if (!StringUtils.hasText(recordPid) || config == null || !StringUtils.hasText(config.getPid())) {
            return null;
        }
        List<SlaRecordEntity> records = slaRecordService.findByProcessInstance(recordPid);
        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.stream()
                .filter(record -> record != null
                        && config.getPid().equals(record.getSlaConfigId())
                        && Objects.equals(config.getTargetKey(), record.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> slaReplayOutputs(
            SlaConfigEntity config,
            String recordPid,
            SlaRecordEntity record) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (config != null) {
            outputs.put("slaConfigPid", config.getPid());
            outputs.put("targetType", config.getTargetType());
            outputs.put("targetKey", config.getTargetKey());
            outputs.put("deadlineMode", config.getDeadlineMode());
            outputs.put("deadlineValue", config.getDeadlineValue());
            outputs.put("enabled", config.getEnabled());
            outputs.put("modelCode", config.getModelCode());
            if (config.getActionPolicy() != null) {
                Object actions = config.getActionPolicy().get("actions");
                outputs.put("actionCount", actions instanceof List<?> list ? list.size() : 0);
                outputs.put("actionPolicyTrigger", config.getActionPolicy().get("trigger"));
            }
        }
        outputs.put("recordPid", recordPid);
        if (record != null) {
            outputs.put("slaRecordPid", record.getPid());
            outputs.put("slaRecordStatus", record.getStatus());
            outputs.put("deadlineTime", record.getDeadlineTime() != null ? record.getDeadlineTime().toString() : null);
            outputs.put("startTime", record.getStartTime() != null ? record.getStartTime().toString() : null);
            outputs.put("nodeId", record.getNodeId());
            outputs.put("processInstanceId", record.getProcessInstanceId());
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private String sampleBpmProcessInstanceId(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return "";
        }
        String processInstanceId = firstString(sampleContext.get("bpm"),
                "processInstanceId", "instanceId", "processId");
        if (StringUtils.hasText(processInstanceId)) {
            return processInstanceId;
        }
        return firstString(sampleContext.get("meta"), "processInstanceId", "bpmProcessInstanceId");
    }

    private String sampleBpmTaskId(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return "";
        }
        String taskId = firstString(sampleContext.get("bpm"), "taskId", "taskInstanceId");
        if (StringUtils.hasText(taskId)) {
            return taskId;
        }
        return firstString(sampleContext.get("meta"), "taskId", "bpmTaskId");
    }

    private String sampleBpmProcessKey(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return "";
        }
        String processKey = firstString(sampleContext.get("bpm"),
                "processKey", "processDefinitionKey", "processDefinitionId");
        if (StringUtils.hasText(processKey)) {
            return processKey;
        }
        return firstString(sampleContext.get("meta"), "processKey", "bpmProcessKey");
    }

    private Long sampleBpmTenantId(Map<String, Map<String, Object>> sampleContext) {
        Long tenantId = firstLong(sampleContext != null ? sampleContext.get("bpm") : null, "tenantId");
        if (tenantId != null) {
            return tenantId;
        }
        tenantId = firstLong(sampleContext != null ? sampleContext.get("meta") : null, "tenantId");
        if (tenantId != null) {
            return tenantId;
        }
        try {
            return MetaContext.getCurrentTenantId();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> slaNodeReplayOutputs(
            SlaConfigEntity config,
            String processInstanceId,
            String taskId,
        SlaRecordEntity record) {
        Map<String, Object> outputs = new LinkedHashMap<>(
                slaReplayOutputs(config, null, record));
        outputs.put("processInstanceId", processInstanceId);
        outputs.put("taskId", taskId);
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private ModelPublishReplayResultDTO replayAutomationStep(
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (automationService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("AUTOMATION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("Automation service is unavailable in this runtime.")
                    .errors(List.of("AUTOMATION_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("Automation replay is available. Pass executeAutomated=true with sampleContext.record.pid to run it.")
                    .errors(List.of())
                    .outputs(automationReplayBaseOutputs(step, null, null))
                    .build();
        }
        if (!StringUtils.hasText(step.getSourcePid())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Automation replay requires sourcePid.")
                    .errors(List.of("MISSING_AUTOMATION_PID"))
                    .outputs(Map.of())
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return automationNeedsSampleContext(step, "Automation replay requires representative sampleContext.");
        }
        String recordPid = sampleRecordPid(request.getSampleContext());
        if (!StringUtils.hasText(recordPid)) {
            return automationNeedsSampleContext(step,
                    "Automation replay requires sampleContext.record.pid or sampleContext.record.recordPid.");
        }

        Map<String, Object> replayContext = new LinkedHashMap<>();
        replayContext.putAll(request.getSampleContext());
        try {
            AutomationLogDTO log = automationService.triggerManually(step.getSourcePid(), recordPid, replayContext);
            boolean failed = automationReplayFailed(log);
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(failed ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!failed)
                    .message(log == null
                            ? "Automation replay returned no execution log."
                            : "Automation replay executed with status " + log.getStatus() + ".")
                    .traceId(log != null ? log.getPid() : null)
                    .matched(log != null && !failed)
                    .outputs(automationReplayBaseOutputs(step, recordPid, log))
                    .errors(automationReplayErrors(log))
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Automation replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(automationReplayBaseOutputs(step, recordPid, null))
                    .build();
        }
    }

    private ModelPublishReplayResultDTO automationNeedsSampleContext(ModelPublishReplayStepDTO step, String message) {
        return ModelPublishReplayResultDTO.builder()
                .step(step)
                .status("NEEDS_SAMPLE_CONTEXT")
                .automated(true)
                .executed(false)
                .message(message)
                .errors(List.of())
                .outputs(automationReplayBaseOutputs(step, null, null))
                .build();
    }

    private String sampleRecordPid(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return "";
        }
        String recordPid = firstString(sampleContext.get("record"), "pid", "recordPid", "publicId");
        if (StringUtils.hasText(recordPid)) {
            return recordPid;
        }
        recordPid = firstString(sampleContext.get("trigger"), "recordPid", "pid");
        if (StringUtils.hasText(recordPid)) {
            return recordPid;
        }
        recordPid = firstString(sampleContext.get("meta"), "recordPid", "recordPublicId");
        if (StringUtils.hasText(recordPid)) {
            return recordPid;
        }
        Map<String, Object> record = sampleContext.get("record");
        if (record != null && record.get("data") instanceof Map<?, ?> data) {
            return firstString(data, "pid", "recordPid", "publicId");
        }
        return "";
    }

    private String firstString(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private Long samplePermissionMemberId(Map<String, Map<String, Object>> sampleContext) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return null;
        }
        Long memberId = firstLong(sampleContext.get("permission"), "memberId", "tenantMemberId", "actorMemberId");
        if (memberId != null) {
            return memberId;
        }
        memberId = firstLong(sampleContext.get("actor"), "memberId", "tenantMemberId", "actorMemberId");
        if (memberId != null) {
            return memberId;
        }
        return firstLong(sampleContext.get("meta"), "memberId", "tenantMemberId", "actorMemberId");
    }

    private Long firstLong(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            Long parsed = parseLong(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String permissionReplayPermissionCode(
            ModelPublishReplayStepDTO step,
            Map<String, Map<String, Object>> sampleContext) {
        String fromContext = firstString(sampleContext != null ? sampleContext.get("permission") : null, "permissionCode");
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
        }
        Map<String, Object> metadata = step != null && step.getMetadata() != null ? step.getMetadata() : Map.of();
        String fromMetadata = metadataText(metadata, "permissionCode");
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        return step != null ? nullToBlank(step.getSourceCode()) : "";
    }

    private String permissionReplayResource(
            ModelPublishReplayStepDTO step,
            Map<String, Map<String, Object>> sampleContext,
            String permissionCode) {
        String fromContext = firstString(sampleContext != null ? sampleContext.get("permission") : null,
                "resource", "resourceCode", "modelCode");
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
        }
        Map<String, Object> metadata = step != null && step.getMetadata() != null ? step.getMetadata() : Map.of();
        String fromMetadata = metadataText(metadata, "resourceCode");
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        return derivePermissionResource(permissionCode);
    }

    private String permissionReplayAction(
            ModelPublishReplayStepDTO step,
            Map<String, Map<String, Object>> sampleContext,
            String permissionCode) {
        String fromContext = firstString(sampleContext != null ? sampleContext.get("permission") : null, "action");
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
        }
        Map<String, Object> metadata = step != null && step.getMetadata() != null ? step.getMetadata() : Map.of();
        String fromMetadata = metadataText(metadata, "action");
        if (StringUtils.hasText(fromMetadata)) {
            return fromMetadata;
        }
        return derivePermissionAction(permissionCode);
    }

    private String derivePermissionResource(String permissionCode) {
        if (!StringUtils.hasText(permissionCode)) {
            return "";
        }
        String code = permissionCode.trim();
        int colon = code.lastIndexOf(':');
        if (colon > 0) {
            return code.substring(0, colon);
        }
        int lastDot = code.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        String resource = code.substring(0, lastDot);
        return resource.startsWith("model.") ? resource.substring("model.".length()) : resource;
    }

    private String derivePermissionAction(String permissionCode) {
        if (!StringUtils.hasText(permissionCode)) {
            return "";
        }
        String code = permissionCode.trim();
        int colon = code.lastIndexOf(':');
        if (colon >= 0 && colon < code.length() - 1) {
            return code.substring(colon + 1);
        }
        int lastDot = code.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < code.length() - 1) {
            return code.substring(lastDot + 1);
        }
        return "";
    }

    private Map<String, Object> permissionReplayRecord(
            Map<String, Map<String, Object>> sampleContext,
            String recordPid) {
        if (sampleContext == null || sampleContext.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> record = new LinkedHashMap<>(sampleRecordData(sampleContext));
        Map<String, Object> rawRecord = sampleContext.get("record");
        if (StringUtils.hasText(recordPid)) {
            record.putIfAbsent("pid", recordPid);
        }
        Long recordId = firstLong(rawRecord, "id", "recordId", "internalId");
        if (recordId != null) {
            record.putIfAbsent("id", recordId);
        }
        if (rawRecord != null) {
            Object meta = rawRecord.get("meta");
            if (meta != null) {
                record.putIfAbsent("meta", meta);
            }
        }
        return record;
    }

    private Map<String, Object> permissionReplayOutputs(
            ModelPublishReplayStepDTO step,
            String permissionCode,
            String resource,
            String action,
            Long memberId,
            String recordPid,
            PermissionResult result) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("permissionPolicyPid", step != null ? step.getSourcePid() : null);
        outputs.put("permissionCode", permissionCode);
        outputs.put("resource", resource);
        outputs.put("action", action);
        outputs.put("memberId", idForBrowser(memberId));
        outputs.put("recordPid", recordPid);
        if (step != null && step.getMetadata() != null) {
            outputs.put("roleId", idForBrowser(step.getMetadata().get("roleId")));
            outputs.put("grantType", step.getMetadata().get("grantType"));
            outputs.put("status", step.getMetadata().get("status"));
            if (hasFieldGovernanceReplayMetadata(step)) {
                outputs.put("affectedFieldRef", step.getFieldRef());
                putReplayMetadataOutput(outputs, step, "fieldRiskLevel");
                putReplayMetadataOutput(outputs, step, "fieldRiskSummary");
                putReplayMetadataOutput(outputs, step, "fieldMasked");
                putReplayMetadataOutput(outputs, step, "fieldPermissionChange");
                putReplayMetadataOutput(outputs, step, "fieldPermission");
                putReplayMetadataOutput(outputs, step, "requiresLowPermissionSample");
            }
        }
        if (result != null) {
            outputs.put("granted", result.granted());
            outputs.put("reason", result.reason());
            outputs.put("stepCount", result.steps() != null ? result.steps().size() : 0);
            outputs.put("steps", permissionReplayStepOutputs(result.steps()));
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private boolean hasFieldGovernanceReplayMetadata(ModelPublishReplayStepDTO step) {
        if (step == null || step.getMetadata() == null) {
            return false;
        }
        return step.getMetadata().containsKey("fieldRiskLevel")
                || step.getMetadata().containsKey("fieldMasked")
                || step.getMetadata().containsKey("fieldPermissionChange")
                || step.getMetadata().containsKey("fieldPermission");
    }

    private void putReplayMetadataOutput(
            Map<String, Object> outputs,
            ModelPublishReplayStepDTO step,
            String key) {
        Object value = step != null && step.getMetadata() != null ? step.getMetadata().get(key) : null;
        if (value != null) {
            outputs.put(key, value);
        }
    }

    private List<Map<String, Object>> permissionReplayStepOutputs(List<EvaluationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(Objects::nonNull)
                .map(step -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("evaluatorName", step.evaluatorName());
                    item.put("verdict", step.verdict() != null ? step.verdict().name() : null);
                    item.put("reason", step.reason());
                    if (step.details() != null && !step.details().isEmpty()) {
                        item.put("details", step.details());
                    }
                    Map<String, Object> cleaned = item.entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (left, right) -> left,
                                    LinkedHashMap::new));
                    return cleaned;
                })
                .toList();
    }

    private String idForBrowser(Object value) {
        if (value instanceof Number number) {
            return number.toString();
        }
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private boolean automationReplayFailed(AutomationLogDTO log) {
        if (log == null) {
            return true;
        }
        String status = nullToBlank(log.getStatus()).toLowerCase(Locale.ROOT);
        return StatusConstants.FAILED.equals(status)
                || "error".equals(status)
                || StatusConstants.CANCELLED.equals(status);
    }

    private List<String> automationReplayErrors(AutomationLogDTO log) {
        if (log == null) {
            return List.of("AUTOMATION_REPLAY_RETURNED_NO_LOG");
        }
        if (StringUtils.hasText(log.getErrorMessage())) {
            return List.of(log.getErrorMessage());
        }
        return List.of();
    }

    private Map<String, Object> automationReplayBaseOutputs(
            ModelPublishReplayStepDTO step,
            String recordPid,
            AutomationLogDTO log) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("automationPid", step != null ? step.getSourcePid() : null);
        outputs.put("automationCode", step != null ? step.getSourceCode() : null);
        outputs.put("recordPid", recordPid);
        if (step != null && step.getMetadata() != null) {
            outputs.put("modelCode", step.getMetadata().get("modelCode"));
            outputs.put("triggerType", step.getMetadata().get("triggerType"));
            outputs.put("enabled", step.getMetadata().get("enabled"));
        }
        if (log != null) {
            outputs.put("logPid", log.getPid());
            outputs.put("logStatus", log.getStatus());
            outputs.put("triggerRecordPid", log.getTriggerRecordPid());
            outputs.put("triggerType", log.getTriggerType());
            outputs.put("durationMs", log.getDurationMs());
            outputs.put("actionCount", log.getActionResults() != null ? log.getActionResults().size() : 0);
            outputs.put("actionResults", log.getActionResults() != null ? log.getActionResults() : List.of());
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private ModelPublishReplayResultDTO replayDecisionStep(
            Model model,
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (decisionEvaluationService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("AUTOMATION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("Decision evaluation service is unavailable in this runtime.")
                    .errors(List.of("DECISION_EVALUATION_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay is available. Pass executeAutomated=true with sampleContext to run it.")
                    .errors(List.of())
                    .outputs(Map.of())
                    .build();
        }
        if (!StringUtils.hasText(step.getSourceCode())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay requires sourceCode.")
                    .errors(List.of("MISSING_DECISION_CODE"))
                    .outputs(Map.of())
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("NEEDS_SAMPLE_CONTEXT")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay requires representative sampleContext.")
                    .errors(List.of())
                    .outputs(Map.of())
                    .build();
        }

        DrtEvaluateRequest evaluateRequest = new DrtEvaluateRequest();
        evaluateRequest.setDecisionCode(step.getSourceCode());
        evaluateRequest.setCallerType("MODEL_PUBLISH_REPLAY");
        evaluateRequest.setCallerRef(StringUtils.hasText(model.getPid()) ? model.getPid() : model.getCode());
        evaluateRequest.setCorrelationId(StringUtils.hasText(request.getCorrelationId())
                ? request.getCorrelationId()
                : "model-publish-replay-" + nullToBlank(model.getPid()));
        evaluateRequest.setContext(request.getSampleContext());
        try {
            DecisionResult result = decisionEvaluationService.evaluate(evaluateRequest);
            boolean error = result != null && result.errors() != null && !result.errors().isEmpty();
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(error ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!error)
                    .message(result == null
                            ? "Decision replay returned no result."
                            : "Decision replay executed with status " + result.status() + ".")
                    .traceId(result != null ? result.traceId() : null)
                    .matched(result != null ? result.matched() : null)
                    .outputs(result != null && result.outputs() != null ? result.outputs() : Map.of())
                    .errors(result != null && result.errors() != null ? result.errors() : List.of())
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("Decision replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(Map.of())
                    .build();
        }
    }

    private ModelPublishReplayResultDTO replayEventPolicyStep(
            Model model,
            ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        if (eventPolicyRuntimeService == null) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("AUTOMATION_UNAVAILABLE")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy runtime service is unavailable in this runtime.")
                    .errors(List.of("EVENT_POLICY_RUNTIME_SERVICE_UNAVAILABLE"))
                    .outputs(Map.of())
                    .build();
        }
        Map<String, Object> metadata = step.getMetadata() == null ? Map.of() : step.getMetadata();
        String eventType = metadataText(metadata, "eventType");
        String targetType = metadataText(metadata, "targetType");
        String targetKey = metadataText(metadata, "targetKey");
        if (!StringUtils.hasText(eventType) || !StringUtils.hasText(targetType) || !StringUtils.hasText(targetKey)) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("MANUAL_REQUIRED")
                    .automated(false)
                    .executed(false)
                    .message("EventPolicy replay requires eventType, targetType and targetKey metadata. Rebuild the Rule Center usage index and retry.")
                    .errors(List.of("MISSING_EVENT_POLICY_REPLAY_METADATA"))
                    .outputs(Map.of())
                    .build();
        }
        if (request == null || !Boolean.TRUE.equals(request.getExecuteAutomated())) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("READY")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy replay is available. Pass executeAutomated=true with sampleContext to run it.")
                    .errors(List.of())
                    .outputs(Map.of("eventType", eventType, "targetType", targetType, "targetKey", targetKey))
                    .build();
        }
        if (request.getSampleContext() == null || request.getSampleContext().isEmpty()) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("NEEDS_SAMPLE_CONTEXT")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy replay requires representative sampleContext.")
                    .errors(List.of())
                    .outputs(Map.of("eventType", eventType, "targetType", targetType, "targetKey", targetKey))
                    .build();
        }

        try {
            EventPolicyExecutionResult result = eventPolicyRuntimeService.runAndExecute(
                    eventType,
                    targetType,
                    targetKey,
                    request.getSampleContext());
            EventPolicyResult policy = result != null ? result.policy() : null;
            PolicyExecutionResult execution = result != null ? result.execution() : null;
            List<String> errors = eventPolicyReplayErrors(policy, execution);
            boolean failed = eventPolicyReplayFailed(policy, execution, errors);
            Map<String, Object> outputs = eventPolicyReplayOutputs(eventType, targetType, targetKey, policy, execution);
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status(failed ? "FAILED" : "EXECUTED")
                    .automated(true)
                    .executed(!failed)
                    .message(policy == null
                            ? "EventPolicy replay returned no policy result."
                            : "EventPolicy replay executed with status " + policy.status() + ".")
                    .traceId(policy != null && StringUtils.hasText(policy.primaryDecisionTraceId())
                            ? policy.primaryDecisionTraceId()
                            : policy != null ? policy.correlationId() : null)
                    .matched(policy != null && policy.status() == EventPolicyResult.Status.MATCHED)
                    .outputs(outputs)
                    .errors(errors)
                    .build();
        } catch (RuntimeException e) {
            return ModelPublishReplayResultDTO.builder()
                    .step(step)
                    .status("FAILED")
                    .automated(true)
                    .executed(false)
                    .message("EventPolicy replay failed: " + e.getMessage())
                    .errors(List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .outputs(Map.of("eventType", eventType, "targetType", targetType, "targetKey", targetKey))
                    .build();
        }
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private boolean eventPolicyReplayFailed(
            EventPolicyResult policy,
            PolicyExecutionResult execution,
            List<String> errors) {
        if (policy == null) {
            return true;
        }
        if (policy.status() == EventPolicyResult.Status.ERROR || policy.status() == EventPolicyResult.Status.CONFLICT) {
            return true;
        }
        if (execution != null && (execution.overallStatus() == PolicyExecutionResult.OverallStatus.FAILED
                || execution.overallStatus() == PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS)) {
            return true;
        }
        return errors != null && !errors.isEmpty()
                && policy.status() != EventPolicyResult.Status.NOT_MATCHED;
    }

    private List<String> eventPolicyReplayErrors(EventPolicyResult policy, PolicyExecutionResult execution) {
        List<String> errors = new ArrayList<>();
        if (policy != null && policy.errors() != null) {
            errors.addAll(policy.errors());
        }
        if (execution != null && execution.actions() != null) {
            for (ActionExecutionResult action : execution.actions()) {
                if (action != null && StringUtils.hasText(action.error())) {
                    errors.add(action.error());
                }
            }
            if (execution.overallStatus() == PolicyExecutionResult.OverallStatus.FAILED
                    || execution.overallStatus() == PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS) {
                errors.add("EVENT_POLICY_EXECUTION_" + execution.overallStatus());
            }
        }
        return List.copyOf(errors);
    }

    private Map<String, Object> eventPolicyReplayOutputs(
            String eventType,
            String targetType,
            String targetKey,
            EventPolicyResult policy,
            PolicyExecutionResult execution) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("eventType", eventType);
        outputs.put("targetType", targetType);
        outputs.put("targetKey", targetKey);
        if (policy != null) {
            outputs.put("policyCode", policy.policyCode());
            outputs.put("policyStatus", policy.status() != null ? policy.status().name() : null);
            outputs.put("correlationId", policy.correlationId());
            outputs.put("primaryDecisionTraceId", policy.primaryDecisionTraceId());
            outputs.put("decisionTraceIds", policy.decisionTraceIds());
            outputs.put("matchedRuleCodes", policy.matchedRuleCodes());
            outputs.put("skippedRuleCodes", policy.skippedRuleCodes());
            outputs.put("actionPlanCount", policy.actionPlans() != null ? policy.actionPlans().size() : 0);
        }
        if (execution != null) {
            outputs.put("executionStatus", execution.overallStatus() != null ? execution.overallStatus().name() : null);
            outputs.put("actionCount", execution.actions() != null ? execution.actions().size() : 0);
            outputs.put("successfulActionCount", execution.successCount());
            outputs.put("actions", eventPolicyActionOutputs(execution.actions()));
        }
        return outputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<Map<String, Object>> eventPolicyActionOutputs(List<ActionExecutionResult> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(Objects::nonNull)
                .map(action -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ruleCode", action.ruleCode());
                    item.put("type", action.type());
                    item.put("status", action.status() != null ? action.status().name() : null);
                    item.put("idempotencyKey", action.idempotencyKey());
                    item.put("error", action.error());
                    item.put("resultPayload", action.resultPayload());
                    Map<String, Object> cleaned = item.entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (left, right) -> left,
                                    LinkedHashMap::new));
                    return cleaned;
                })
                .toList();
    }

    private String normalizeReplayConsumerType(String sourceType) {
        String normalized = nullToBlank(sourceType).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BPM" -> "BPM_PROCESS";
            case "SLA" -> "SLA_RULE";
            case "EVENT" -> "EVENT_POLICY";
            case "PERMISSION" -> "PERMISSION_POLICY";
            case "DECISION" -> "DECISION_VERSION";
            default -> normalized;
        };
    }

    private String replayConsumerLabel(String consumerType) {
        return switch (nullToBlank(consumerType)) {
            case "DECISION_VERSION" -> "决策版本";
            case "BPM_PROCESS" -> "BPM 流程";
            case "SLA_RULE" -> "SLA 策略";
            case "AUTOMATION" -> "自动化";
            case "EVENT_POLICY" -> "事件策略";
            case "PERMISSION_POLICY" -> "权限策略";
            case "NAMED_QUERY" -> "命名查询";
            default -> "规则消费方";
        };
    }

    private String replayRecommendedAction(String consumerType) {
        return switch (nullToBlank(consumerType)) {
            case "DECISION_VERSION" -> "重新校验并发布受影响决策版本，使用代表性记录回放命中结果。";
            case "BPM_PROCESS" -> "打开 BPMN 设计器校验规则绑定、候选人、网关条件和服务任务参数，重新部署流程。";
            case "SLA_RULE" -> "重新校验 SLA 条件与超时动作，回放 deadline 和升级策略。";
            case "AUTOMATION" -> "重新校验自动化触发条件和动作字段映射，执行一次测试运行。";
            case "EVENT_POLICY" -> "重新校验事件条件、动作 payload 和 Trace 链路，执行一次策略回放。";
            case "PERMISSION_POLICY" -> "重新校验 ABAC 条件与 allow/deny 矩阵，执行权限审计回放。";
            case "NAMED_QUERY" -> "重新校验查询字段、下游可视化和导出消费。";
            default -> "复核字段引用、重新发布或回放该消费方。";
        };
    }

    private String buildHistoricalVersionPolicy(Model model) {
        Integer latestPublishedVersion = latestPublishedVersion(model);
        if (latestPublishedVersion == null) {
            return "Initial publish: no historical published model version exists. Rule consumers should bind to this published schema after publish.";
        }
        return "Latest-compatible policy: publishing this draft makes it the current model metadata. Existing published rule, BPM, SLA, Automation and EventPolicy versions keep their own versioned assets, but consumers using latest model fields must be replayed and republished after acknowledgement.";
    }

    private Integer latestPublishedVersion(Model model) {
        if (model == null || !StringUtils.hasText(model.getCode())) {
            return null;
        }
        List<Model> versions = metaModelMapper.findAllVersionsByCode(model.getCode());
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.stream()
                .filter(version -> StatusConstants.PUBLISHED.equals(version.getStatus()))
                .map(Model::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private String governanceImpactSummary(ModelPublishGovernanceDTO governance) {
        if (governance == null || governance.getFieldImpacts() == null || governance.getFieldImpacts().isEmpty()) {
            return "schema change detected";
        }
        return governance.getFieldImpacts().stream()
                .map(impact -> impact.getFieldRef() + " -> "
                        + (impact.getRisk() != null ? impact.getRisk().getSummary() : "unknown impact"))
                .collect(Collectors.joining("; "));
    }

    private void recordModelPublishAcknowledgement(Model model, ModelPublishGovernanceDTO governance, String note) {
        if (decisionImpactAckService == null) {
            log.warn("Decision impact acknowledgement service unavailable; model publish ack not persisted: model={}",
                    logSafe(model.getCode()));
            return;
        }
        decisionImpactAckService.recordAcknowledgement(
                "MODEL_PUBLISH",
                "MODEL",
                model.getCode(),
                model.getPid(),
                model.getCode(),
                governanceImpactSummary(governance),
                governance,
                note);
    }
}
