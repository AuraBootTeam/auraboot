package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.service.AutomationService;
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
import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.beans.factory.annotation.Value;
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

    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final QueryBuilderService queryBuilderService;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissionAssignmentService;
    private final MetaDefinitionCacheService metaDefinitionCacheService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ApplicationRuntimeDefinitionCatalog applicationRuntimeDefinitionCatalog;

    @Value("${aura.application.default-code:}")
    private String defaultApplicationCode;

    @Value("${aura.application.definition-read.runtime-primary-enabled:false}")
    private boolean applicationRuntimePrimaryEnabled;

    @Autowired
    @Lazy
    private SchemaManagementService schemaManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ObjectProvider<com.auraboot.framework.meta.spi.MoneyFieldExpansionSpi> moneyFieldTypeHandlerProvider;

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
    private PermissionEvaluator permissionEvaluator;

    @Autowired
    private com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry workflowCapabilities;

    @Override
    public Optional<ModelDefinition> getModelDefinition(String modelCode) {
        validateModelCode(modelCode);
        Optional<ModelDefinition> releaseModel = releaseModel(modelCode);
        if (releaseModel.isPresent()) {
            return releaseModel;
        }
        return metaDefinitionCacheService.getModelDefinition(
                modelCode,
                () -> loadModelDefinition(modelCode));
    }

    private Optional<ModelDefinition> releaseModel(String modelCode) {
        if (!applicationRuntimePrimaryEnabled || !StringUtils.hasText(defaultApplicationCode)
                || !MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            return Optional.empty();
        }
        return applicationRuntimeDefinitionCatalog.findModel(
                MetaContext.getCurrentTenantId(), defaultApplicationCode.trim(), modelCode);
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
    public void refreshModelCache(String modelCode) {
        // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
        log.info("Refreshing model cache for: {} in tenant: {}", logSafe(modelCode), getCurrentTenantId());
        // Evict via the separate cache bean so the @CacheEvict interceptor fires even when
        // this method is reached by self-invocation (evictModelCache / publish paths). A
        // @CacheEvict annotation placed directly here is silently bypassed on internal calls.
        metaDefinitionCacheService.evict(modelCode);
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
        if (StringUtils.hasText(model.getPluginPid())) {
            autoPermissionAssignmentService.registerPermissions(model.getCode(), null, model.getTenantId());
        } else {
            autoPermissionAssignmentService.autoAssignPermissions(request.getCode(), null);
        }
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
            // A9: id/pid are system-generated on record create — marking them
            // required makes every direct dynamicDataService.create fail validation
            // ("Required field 'pid' is missing") before the platform can stamp ids.
            binding.setRequired(false);
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
        return modelDefinitionProjection().convertToModelDefinition(model);
    }

    /**
     * Hydrate the capabilities JSONB string into a ModelCapabilities value object.
     * Empty / null → ModelCapabilities.empty().
     */
    private ModelCapabilities parseCapabilities(String json) {
        return modelDefinitionProjection().parseCapabilities(json);
    }

    /**
     * Normalize caller-supplied capabilities so that sortableFields / filterableFields
     * reflect the field-level sortable/filterable flags on {@link ModelDefinition#getFields()}.
     *
     * Per design §3.3: 字段级 sortable/filterable 是编辑态 UI 输入，capabilities 白名单是运行时事实.
     * Any caller-supplied whitelist is OVERRIDDEN here.
     */
    private ModelCapabilities normalizeCapabilities(ModelDefinition def) {
        return modelDefinitionProjection().normalizeCapabilities(def);
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
            // Immutable is monotonic: a definition may opt in, while a partial update whose
            // primitive boolean defaults to false must never silently remove the invariant.
            if (def.isImmutable()) {
                inner.put("immutable", true);
            }
            if (def.isCommandOnlyCreate()) {
                inner.put("commandOnlyCreate", true);
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
        if (def.isImmutable()) {
            extensionData.put("immutable", true);
        }
        if (def.isCommandOnlyCreate()) {
            extensionData.put("commandOnlyCreate", true);
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
        return modelDefinitionProjection().loadCrossFieldRules(model);
    }

    /**
     * Resolve table name:
     * 1. entity.tableName (first-class column)
     * 2. generated default "ab_dyn_{code}"
     */
    private String resolveTableName(Model model) {
        return modelDefinitionProjection().resolveTableName(model);
    }

    /**
     * Flatten Model.extension into a single map so executors can read config like
     * {@code endpointAdapter} regardless of whether it sits in the nested
     * {@code extension.extension} payload or at the flat top level.
     * Flat keys override nested ones when both exist.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenExtension(Model model) {
        return modelDefinitionProjection().flattenExtension(model);
    }

    /**
     * Resolve softDelete flag from extension.softDelete.
     * When true, delete operations use UPDATE deleted_flag=true instead of physical DELETE,
     * and queries automatically filter out soft-deleted records.
     */
    private boolean resolveSoftDelete(Model model) {
        return modelDefinitionProjection().resolveSoftDelete(model);
    }

    /** Resolve the append-only invariant from extension.immutable. */
    private boolean resolveImmutable(Model model) {
        return modelDefinitionProjection().resolveImmutable(model);
    }

    /** Resolve the command-only creation invariant from extension.commandOnlyCreate. */
    private boolean resolveCommandOnlyCreate(Model model) {
        return modelDefinitionProjection().resolveCommandOnlyCreate(model);
    }

    /**
     * 加载字段定义（优化版 - 批量查询避免N+1问题）
     * 自动补充系统字段：id, pid, created_at, updated_at, tenant_id 等
     */
    private List<FieldDefinition> loadFieldDefinitions(Long modelId) {
        return fieldDefinitionAssembler().loadFieldDefinitions(modelId);
    }

    private List<FieldDefinition> mergeDeclaredExtensionFields(
            ModelDefinition modelDefinition,
            List<FieldDefinition> boundFields) {
        return fieldDefinitionAssembler().mergeDeclaredExtensionFields(modelDefinition,boundFields);
    }

    /**
     * 补充系统字段定义（如果不存在）
     * 系统字段包括：id, pid, created_at, updated_at, created_by, updated_by, tenant_id
     */
    private void appendSystemFieldsIfMissing(List<FieldDefinition> fields, Set<String> existingCodes) {
        fieldDefinitionAssembler().appendSystemFieldsIfMissing(fields,existingCodes);
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
        return modelDefinitionProjection().loadModelRelations(modelId);
    }

    /**
     * Build a single {@link RelationDefinition} from a reference field, or {@code null} when the
     * field does not declare a bidirectional relation (target entity + parseable relation type).
     */
    private RelationDefinition buildRelationDefinition(Field field, String sourceModel, String sourceTable) {
        return modelDefinitionProjection().buildRelationDefinition(field,sourceModel,sourceTable);
    }

    private RelationDefinition.RelationType parseRelationType(String raw) {
        return modelDefinitionProjection().parseRelationType(raw);
    }

    /**
     * 根据模型编码生成表名
     * 使用独立表模式，每个模型对应一个独立的表
     */
    private String generateTableName(String modelCode) {
        return modelDefinitionProjection().generateTableName(modelCode);
    }

    /**
     * 将MetaModel实体转换为MetaModelDTO
     */
    private MetaModelDTO convertToMetaModelDTO(Model model) {
        return modelDefinitionProjection().convertToMetaModelDTO(model);
    }

    private MetaModelDTO convertToMetaModelDTO(Model model, Integer fieldCount) {
        return modelDefinitionProjection().convertToMetaModelDTO(model,fieldCount);
    }

    private Map<Long, Integer> loadUserFieldCountsByModelId(List<Model> models) {
        return modelDefinitionProjection().loadUserFieldCountsByModelId(models);
    }

    /**
     * Convert ExtensionBean to a flat Map for DTO.
     * Merges nested "extension" sub-map and top-level dynamic properties.
     */
    private Map<String, Object> convertExtensionToMap(ExtensionBean bean) {
        return fieldDefinitionAssembler().convertExtensionToMap(bean);
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
        return fieldDefinitionAssembler().flattenFieldExtension(bean);
    }

    /**
     * 将FieldEntity转换为FieldDefinition
     */
    private FieldDefinition convertToFieldDefinition(Field field, Integer sortOrder) {
        return fieldDefinitionAssembler().convertToFieldDefinition(field,sortOrder);
    }

    private FieldDefinition.ImmutableWhen readImmutableWhen(Object raw) {
        return fieldDefinitionAssembler().readImmutableWhen(raw);
    }

    /** Missing declaration is unrestricted; malformed persisted metadata denies every writer. */
    private List<String> readAllowedWriterCommands(Map<String, Object> extensionMap) {
        return fieldDefinitionAssembler().readAllowedWriterCommands(extensionMap);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNestedMap(Object raw) {
        return fieldDefinitionAssembler().extractNestedMap(raw);
    }

    private Integer readInteger(Map<String, Object> primary, Map<String, Object> constraints, String key) {
        return fieldDefinitionAssembler().readInteger(primary,constraints,key);
    }

    private Object readValue(Map<String, Object> primary, Map<String, Object> constraints, String primaryKey, String fallbackKey) {
        return fieldDefinitionAssembler().readValue(primary,constraints,primaryKey,fallbackKey);
    }

    private FieldDefinition.RefTarget convertRefTargetBeanToDto(FieldRefTargetBean bean) {
        return fieldDefinitionAssembler().convertRefTargetBeanToDto(bean);
    }

    /**
     * 根据字段键生成列名
     */
    private String generateColumnName(String code) {
        return fieldDefinitionAssembler().generateColumnName(code);
    }

    private String resolveColumnName(String fieldCode, Map<String, Object> extension) {
        return fieldDefinitionAssembler().resolveColumnName(fieldCode,extension);
    }

    /**
     * 创建数据类型映射
     */
    private DataTypeMapping createDataTypeMapping(String dataType) {
        return fieldDefinitionAssembler().createDataTypeMapping(dataType);
    }

    /**
     * 映射到Java类型
     */
    private String mapToJavaType(String logicalType) {
        return fieldDefinitionAssembler().mapToJavaType(logicalType);
    }

    /**
     * 映射到物理类型
     */
    private String mapToPhysicalType(String logicalType) {
        return fieldDefinitionAssembler().mapToPhysicalType(logicalType);
    }

    /**
     * 映射到JDBC类型
     */
    private String mapToJdbcType(String logicalType) {
        return fieldDefinitionAssembler().mapToJdbcType(logicalType);
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
        return modelFieldBindingSupport().isFieldBoundToModel(modelId,fieldId);
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
        return modelFieldBindingSupport().bindFieldToModel(modelId,fieldId,fieldOrder,required,visible,editable,defaultValue,validationRules,displayConfig,remarks);
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
        return modelFieldBindingSupport().unbindFieldFromModel(modelId,fieldId);
    }

    @Override
    @Cacheable(value = "modelFieldBindings", key = "#modelId + '_' + #includeDetails + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()")
    public List<ModelFieldBinding> getModelFieldBindings(Long modelId, Boolean includeDetails) {
        return modelFieldBindingSupport().getModelFieldBindings(modelId,includeDetails);
    }

    @Override
    public Optional<ModelFieldBinding> getFieldBinding(Long modelId, Long fieldId) {
        return modelFieldBindingSupport().getFieldBinding(modelId,fieldId);
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
        return modelFieldBindingSupport().updateFieldBinding(binding);
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
        return modelHistoryQuerySupport().getVersionHistory(code);
    }

    @Override
    public MetaModelDTO getVersionDetail(String code, Integer version) {
        return modelHistoryQuerySupport().getVersionDetail(code,version);
    }

    @Override
    public Map<String, Object> compareVersions(String code, Integer v1, Integer v2) {
        return modelHistoryQuerySupport().compareVersions(code,v1,v2);
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
        return modelHistoryQuerySupport().getStatistics();
    }

    @Override
    public PageResult<MetaModelDTO> searchModels(
            Integer page, Integer size, String keyword, String code, String displayName,
            String modelType, String status, String sourceType, String sortField, String sortOrder, Boolean currentOnly) {
        return modelHistoryQuerySupport().searchModels(page,size,keyword,code,displayName,modelType,status,sourceType,sortField,sortOrder,currentOnly);
    }

    @Override
    public Map<String, Object> validateModelData(Map<String, Object> modelData) {
        return modelHistoryQuerySupport().validateModelData(modelData);
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

        // External sources publish metadata without creating a backing entity table.
        boolean externalSource = model.getSourceType() != null && !"physical".equals(model.getSourceType());
        if (externalSource || model.isViewType() || model.isSkipTableCreation()) {
            log.info("Publishing model (no table creation): pid={}, code={}, reason={}",
                    logSafe(pid), logSafe(model.getCode()),
                    externalSource ? "external source" : model.isViewType() ? "VIEW model" : "skipTableCreation=true");
        } else {
            // Validate: must have at least one field binding
            List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(model.getId());
            if (bindings == null || bindings.isEmpty()) {
                throw new MetaServiceException("Model must have at least one field bound before publishing");
            }

            // Keep the keyword surface at a sensible minimum (additive; explicit marks win)
            autoMarkSearchableFields(model.getId(), bindings);

            // Expand MONEY type fields (auto-create _base fields, currency headers, binding rules)
            // moneyFieldTypeHandler is provided by the enterprise finance module; absent in core-only deploys.
            com.auraboot.framework.meta.spi.MoneyFieldExpansionSpi moneyFieldTypeHandler =
                    moneyFieldTypeHandlerProvider.getIfAvailable();
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

            // The dynamic table now exists. Post-publish hooks run synchronously inside the same
            // transaction: a failing hook (e.g. an installable guard trigger) fails the publish,
            // so a model can never end up published without a guard it declared.
            eventPublisher.publishEvent(new com.auraboot.framework.meta.event.ModelTablePublishedEvent(this, model.getCode()));
        }

        // Update model status
        model.setStatus(StatusConstants.PUBLISHED);
        model.setIsCurrent(true);
        model.setUpdatedAt(Instant.now());
        metaModelMapper.updateById(model);

        log.info("Model published successfully: pid={}, code={}", logSafe(pid), logSafe(model.getCode()));

        // Auto-create hierarchical permissions for the published model
        if (StringUtils.hasText(model.getPluginPid())) {
            autoPermissionAssignmentService.registerPermissions(model.getCode(), null, model.getTenantId());
        } else {
            autoPermissionAssignmentService.autoAssignPermissions(model.getCode(), null);
        }
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
        ModelDefaultPageSupport.create(model, pageSchemaMapper, objectMapper,
                tenantId -> environmentService.findOrCreateDefaultId(tenantId), log);
    }

    /**
     * Ensure keyword search always covers the model's business identity fields
     * (name/code-style string/text bindings), additively and idempotently.
     * Called during model publish. Explicitly searchable bindings stay untouched;
     * when fewer than maxAutoSearchable string/text bindings are searchable in
     * total (e.g. a plugin binding file marks only its add-on enum fields), the
     * first string/text bindings in field order are marked so the keyword surface
     * never degrades to a narrow add-on subset.
     */
    private void autoMarkSearchableFields(Long modelId, List<ModelFieldBinding> bindings) {
        Set<String> searchableTypes = Set.of("string", "text", "enum", "dict");
        int maxAutoSearchable = 5;

        List<ModelFieldBinding> ordered = bindings.stream()
                .sorted(Comparator.comparing(
                        b -> b.getFieldOrder() != null ? b.getFieldOrder() : Integer.MAX_VALUE))
                .toList();
        List<Long> fieldIds = ordered.stream()
                .map(ModelFieldBinding::getFieldId)
                .toList();
        Map<Long, Field> fieldMap = metaFieldMapper.findByIds(fieldIds).stream()
                .collect(Collectors.toMap(Field::getId, f -> f));

        int searchableTextFields = 0;
        for (ModelFieldBinding binding : ordered) {
            Field field = fieldMap.get(binding.getFieldId());
            if (field == null) continue;
            String dt = field.getDataType() != null ? field.getDataType().toUpperCase() : "";
            if (!searchableTypes.contains(dt)) continue;
            if (Boolean.TRUE.equals(binding.getSearchable())) searchableTextFields++;
        }

        int marked = 0;
        for (ModelFieldBinding binding : ordered) {
            if (searchableTextFields + marked >= maxAutoSearchable) break;
            Field field = fieldMap.get(binding.getFieldId());
            if (field == null) continue;
            String dt = field.getDataType() != null ? field.getDataType().toUpperCase() : "";
            if (!searchableTypes.contains(dt)) continue;
            // Skip system fields
            String code = field.getCode();
            if (code == null) continue;
            if (Set.of("pid", "created_by", "updated_by", "tenant_id").contains(code)) continue;
            if (Boolean.TRUE.equals(binding.getSearchable())) continue;

            binding.setSearchable(true);
            binding.setUpdatedAt(Instant.now());
            fieldBindingMapper.updateById(binding);
            marked++;
            log.debug("Auto-marked field {} as searchable for model {}", logSafe(code), modelId);
        }

        if (marked > 0) {
            log.info("Auto-marked {} fields as searchable for model {} ({} already marked)",
                    marked, modelId, searchableTextFields);
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
        return publishGovernanceSupport().replayReport(model, governance, request);
    }

    private ModelPublishGovernanceSupport publishGovernanceSupport() {
        return new ModelPublishGovernanceSupport(metaModelMapper, metaFieldMapper, fieldBindingMapper,
                decisionImpactService, decisionImpactAckService, decisionEvaluationService,
                eventPolicyRuntimeService, automationService, permissionEvaluator,
                new ModelPublishWorkflowReplaySupport(workflowCapabilities, objectMapper), this::flattenFieldExtension);
    }

    private ModelPublishGovernanceDTO buildPublishGovernance(Model model, DDLPreviewResult ddlPreview) {
        return publishGovernanceSupport().buildPublishGovernance(model, ddlPreview);
    }

    private ModelPublishReplayResultDTO replayPublishStep(Model model, ModelPublishReplayStepDTO step,
            MetaModelPublishReplayRequest request) {
        return publishGovernanceSupport().replayPublishStep(model, step, request);
    }

    private String governanceImpactSummary(ModelPublishGovernanceDTO governance) {
        return publishGovernanceSupport().governanceImpactSummary(governance);
    }

    private void recordModelPublishAcknowledgement(Model model, ModelPublishGovernanceDTO governance, String note) {
        publishGovernanceSupport().recordModelPublishAcknowledgement(model, governance, note);
    }

    private ModelFieldDefinitionAssembler fieldDefinitionAssembler() {
        return new ModelFieldDefinitionAssembler(fieldBindingMapper, metaFieldMapper, objectMapper);
    }

    private ModelDefinitionProjection modelDefinitionProjection() {
        return new ModelDefinitionProjection(metaModelMapper, metaFieldMapper, fieldBindingMapper, objectMapper, this::convertExtensionToMap);
    }

    private ModelFieldBindingSupport modelFieldBindingSupport() {
        return new ModelFieldBindingSupport(metaModelMapper, metaFieldMapper, fieldBindingMapper, schemaManagementService, this::isModelExists, this::isFieldExists, this::isModelExists, this::isFieldExists);
    }

    private ModelHistoryQuerySupport modelHistoryQuerySupport() {
        return new ModelHistoryQuerySupport(metaModelMapper, this::isCodeUnique, this::convertToMetaModelDTO, this::convertToMetaModelDTO, this::loadUserFieldCountsByModelId);
    }
}
