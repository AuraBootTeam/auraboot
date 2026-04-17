package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.meta.service.executor.ModelDataExecutor;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态数据服务实现
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicDataServiceImpl extends BaseMetaService implements DynamicDataService {

    private final MetaModelService metadataService;
    private final QueryBuilderService queryBuilderService;
    private final ValidationService validationService;
    private final NamedQueryService namedQueryService;
    private final SecureSqlRewriter secureSqlRewriter;
    private final TypeSystemManager typeSystemManager;
    private final DynamicDataMapper dynamicDataMapper;
    private final SchemaManagementService schemaManagementService;
    private final TableMetadataService tableMetadataService;
    private final ObjectMapper objectMapper;
    private final VirtualFieldEngine virtualFieldEngine;
    private final ChangeTracker changeTracker;
    private final UserMapper userMapper;
    private final FileService fileService;
    private final DataPermissionEngine dataPermissionEngine;
    private final FieldMaskService fieldMaskService;
    private final DataDomainService dataDomainService;
    private final MetaModelMapper metaModelMapper;
    private final ApplicationContext applicationContext;
    private final PayloadTemporalNormalizer payloadTemporalNormalizer;
    private final FieldPermissionService fieldPermissionService;
    private final ExecutorRegistry executorRegistry;
    private static final Set<String> SYSTEM_COLUMNS = SystemFieldConstants.QUERY_TRANSPARENT;

    /**
     * Build a map from field code to display label.
     * Falls back to field code if displayName is null/blank.
     */
    static Map<String, String> buildFieldLabelMap(List<FieldDefinition> fieldDefs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (fieldDefs == null) {
            return map;
        }
        for (FieldDefinition fd : fieldDefs) {
            String label = (fd.getDisplayName() != null && !fd.getDisplayName().isBlank())
                    ? fd.getDisplayName() : fd.getCode();
            map.put(fd.getCode(), label);
        }
        return map;
    }

    // Lazy lookup to break circular dependency: DynamicDataService → AutomationTriggerService → CreateRecordExecutor → DynamicDataService
    private AutomationTriggerService getAutomationTriggerService() {
        return applicationContext.getBean(AutomationTriggerService.class);
    }

    @Override
    @Observed(name = "dynamic_data.list", contextualName = "dynamic-data-list")
    public PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request) {
        validateModelCode(modelCode);
        logOperation("list", modelCode, request);

        // 获取模型定义
        ModelDefinition model = getModelDefinition(modelCode);

        // Phase 1 virtual-model dispatch: if the model has a non-physical sourceType
        // AND an executor is registered for it, delegate. Otherwise fall through to
        // the existing physical-table inline path (preserves full backward compatibility).
        Optional<ModelDataExecutor> executorOpt = executorRegistry.resolve(model.getSourceType());
        if (executorOpt.isPresent()) {
            return executorOpt.get().list(modelCode, request);
        }

        // VIEW models have no physical table — delegate to NamedQuery
        if ("view".equals(model.getModelType())) {
            return listFromNamedQuery(resolveViewNamedQueryCode(modelCode), request);
        }

        // 构建查询
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(
                model, request.getConditions());

        // Keyset pagination flag — when cursor is present, sort is forced to ORDER BY id ASC
        boolean useCursor = request.getCursor() != null;

        // 添加排序 (skipped in cursor mode — cursor pagination requires ORDER BY id ASC)
        if (!useCursor && request.getSortFields() != null && !request.getSortFields().isEmpty()) {
            List<SortField> mappedSortFields = mapSortFields(model, request.getSortFields());
            queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, mappedSortFields, model);
        }

        // 添加租户条件
        Long tenantId = getCurrentTenantId();
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        // 添加数据权限行级过滤 — fail-secure: exception = deny all
        try {
            Long userId = getCurrentUserId();
            String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission for model {} — returning empty result for security", modelCode, e);
            throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode);
        }

        // Apply data domain isolation filter (D5) — fail-secure
        try {
            Long userId = getCurrentUserId();
            String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply domain filter for model {} — returning empty result for security", modelCode, e);
            throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode);
        }

        // Add keyword search across searchable fields
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            queryBuilder = queryBuilderService.buildKeywordSearch(queryBuilder, request.getKeyword(), model);
        }

        // Keyset (cursor-based) pagination: when cursor is provided, use WHERE id > cursor
        // instead of OFFSET for O(1) deep pagination performance.
        if (useCursor) {
            queryBuilder.addCondition("id", "GT", request.getCursor());
            // Force ORDER BY id ASC for consistent cursor traversal
            queryBuilder.addOrderBy("id", "ASC");
            queryBuilder.setLimit(Math.min(request.getPageSize(), 1000));
        } else {
            // Traditional offset pagination
            PaginationRequest pageRequest = new PaginationRequest(
                    request.getPageNum(),
                    request.getPageSize(),
                    request.getKeyword()
            );
            queryBuilder = queryBuilderService.buildPaginationQuery(queryBuilder, pageRequest);
        }
        
        // 验证查询安全性
        QueryValidationResult validation = queryBuilderService.validateQuery(queryBuilder);
        if (!validation.isValid()) {
            throw new MetaServiceException("Query validation failed: " + validation.getErrorMessage());
        }
        
        // 手动添加租户ID条件到SQL和参数中
        String sql = queryBuilder.getSql();
        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        
        // 执行查询
        List<Map<String, Object>> records = dynamicDataMapper.selectByQuery(sql, paramMap);
        
        // Build count query with same filters (including row-level data permission)
        QueryBuilderService.QueryBuilder countBuilder = queryBuilderService.buildConditionQuery(
                model, request.getConditions());
        countBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        // Apply the same row-level filter to count query for consistency — fail-secure
        try {
            Long countUserId = getCurrentUserId();
            String countRowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, countUserId);
            if (countRowFilter != null && !countRowFilter.isBlank()) {
                countBuilder.addRawCondition(countRowFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission to count query for model {} — denying access", modelCode, e);
            throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode);
        }

        // Apply the same domain filter to count query for consistency (D5) — fail-secure
        try {
            Long countUserId = getCurrentUserId();
            String countDomainFilter = dataDomainService.buildDomainFilter(modelCode, countUserId);
            if (countDomainFilter != null && !countDomainFilter.isBlank()) {
                countBuilder.addRawCondition(countDomainFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply domain filter to count query for model {} — denying access", modelCode, e);
            throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode);
        }

        // Apply the same keyword search to count query for consistency
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            queryBuilderService.buildKeywordSearch(countBuilder, request.getKeyword(), model);
        }

        // Rewrite to count SQL
        String countSql = secureSqlRewriter.rewriteForCount(countBuilder.getSql());
        Map<String, Object> countParamMap = countBuilder.getParameterMap();

        Long total = dynamicDataMapper.countByQuery(countSql, countParamMap);

        // 应用列级字段脱敏 (policy-based) — fail-secure: masking failure = deny access
        try {
            Long userId = getCurrentUserId();
            List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
            if (maskRules != null && !maskRules.isEmpty()) {
                records = dataPermissionEngine.applyFieldMasking(records, maskRules);
            }
        } catch (Exception e) {
            log.error("Failed to apply field masking for model {} — returning empty result for security", modelCode, e);
            throw new MetaServiceException("Field masking evaluation failed for model: " + modelCode);
        }

        // Apply configurable field masking (A9) — fail-secure
        try {
            Long userId = getCurrentUserId();
            records = fieldMaskService.applyMaskingForList(modelCode, records, userId);
        } catch (Exception e) {
            log.error("Failed to apply configurable field masking for model {} — returning empty result for security", modelCode, e);
            throw new MetaServiceException("Configurable field masking failed for model: " + modelCode);
        }

        // Apply field-level permission filtering — remove hidden fields from results
        records = applyFieldPermissionFilter(modelCode, records);

        records = enrichListRecords(modelCode, records);

        if (useCursor) {
            // Extract nextCursor from the last record's id
            Long nextCursor = null;
            if (!records.isEmpty()) {
                Object lastId = records.get(records.size() - 1).get("id");
                if (lastId instanceof Number) {
                    nextCursor = ((Number) lastId).longValue();
                }
            }
            return PaginationResult.ofCursor(
                    records,
                    total,
                    request.getPageSize(),
                    nextCursor
            );
        }

        return PaginationResult.of(
                records,
                total,
                request.getPageNum(),
                request.getPageSize()
        );
    }

    /**
     * Apply field-level permission filtering to a list of records.
     * Removes keys that are in the hiddenFields set.
     */
    private List<Map<String, Object>> applyFieldPermissionFilter(String modelCode, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }
        try {
            Long userId = getCurrentUserId();
            FieldPermissionSet fieldPerms = fieldPermissionService.getFieldPermissions(userId, modelCode);
            if (fieldPerms.hiddenFields().isEmpty()) {
                return records;
            }
            Set<String> hidden = fieldPerms.hiddenFields();
            for (Map<String, Object> record : records) {
                hidden.forEach(record::remove);
            }
        } catch (Exception e) {
            log.warn("Failed to apply field permission filter for model {}: {}", modelCode, e.getMessage());
        }
        return records;
    }

    /**
     * Apply field-level permission filtering to a single record.
     */
    private Map<String, Object> applyFieldPermissionFilterSingle(String modelCode, Map<String, Object> record) {
        if (record == null) {
            return record;
        }
        try {
            Long userId = getCurrentUserId();
            FieldPermissionSet fieldPerms = fieldPermissionService.getFieldPermissions(userId, modelCode);
            if (fieldPerms.hiddenFields().isEmpty()) {
                return record;
            }
            fieldPerms.hiddenFields().forEach(record::remove);
        } catch (Exception e) {
            log.warn("Failed to apply field permission filter for model {}: {}", modelCode, e.getMessage());
        }
        return record;
    }

    private List<Map<String, Object>> enrichListRecords(String modelCode, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        // Generic REFERENCE field lookup enrichment (GAP-124)
        enrichReferenceDisplayFields(modelCode, records);

        if (!"tenant_member".equals(modelCode)) {
            return records;
        }

        Set<Long> userIds = records.stream()
                .map(record -> asLong(record.get("user_id")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return records;
        }

        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        for (Map<String, Object> record : records) {
            Long userId = asLong(record.get("user_id"));
            if (userId == null) {
                continue;
            }
            User user = userMap.get(userId);
            if (user == null) {
                continue;
            }

            String nickName = user.getNickName();
            String username = user.getUserName();
            String displayName = (nickName != null && !nickName.isBlank())
                    ? nickName
                    : ((username != null && !username.isBlank()) ? username : String.valueOf(userId));

            record.put("user_name", displayName);
            record.put("user_nick_name", nickName);
            record.put("user_username", username);
            record.put("user_email", user.getEmail());

            if (user.getImgId() != null && !user.getImgId().isBlank()) {
                try {
                    record.put("user_avatar_url", fileService.getFileDownloadUrl(user.getImgId()));
                } catch (Exception e) {
                    log.warn("Failed to resolve avatar URL for userId={}: {}", userId, e.getMessage());
                }
            }
        }
        return records;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Enrich REFERENCE fields with display values from target models (GAP-124 Lookup Field).
     * For each REFERENCE field with a configured refTarget in extraProps, batch-fetches
     * referenced records and populates a synthetic "{fieldCode}_display" column.
     * <p>
     * Reference config is stored in {@code FieldDefinition.extraProps} under key "refTarget"
     * as a Map with keys: targetEntity (model code), displayField (field code to show).
     */
    @SuppressWarnings("unchecked")
    private void enrichReferenceDisplayFields(String modelCode, List<Map<String, Object>> records) {
        Optional<ModelDefinition> modelOpt = metadataService.getModelDefinition(modelCode);
        if (modelOpt.isEmpty()) return;

        ModelDefinition model = modelOpt.get();
        // Reference config: prefer FieldDefinition.refTarget, fallback to extraProps.refTarget
        List<FieldDefinition> refFields = model.getFields().stream()
                .filter(f -> "reference".equals(f.getDataType()))
                .filter(f -> {
                    if (f.getRefTarget() != null && f.getRefTarget().getTargetEntity() != null) return true;
                    return f.getExtraProps() != null && f.getExtraProps().get("refTarget") instanceof Map;
                })
                .toList();

        if (refFields.isEmpty()) return;

        for (FieldDefinition refField : refFields) {
            String fieldCode = refField.getCode();
            String columnName = refField.getColumnName() != null ? refField.getColumnName() : fieldCode;

            String targetModelCode;
            String displayField;
            if (refField.getRefTarget() != null && refField.getRefTarget().getTargetEntity() != null) {
                targetModelCode = refField.getRefTarget().getTargetEntity();
                displayField = refField.getRefTarget().getDisplayField();
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> refTargetMap = (Map<String, Object>) refField.getExtraProps().get("refTarget");
                targetModelCode = refTargetMap.get("targetEntity") instanceof String s ? s : null;
                if (targetModelCode == null) {
                    targetModelCode = refTargetMap.get("targetModel") instanceof String s2 ? s2 : null;
                }
                displayField = refTargetMap.get("displayField") instanceof String s3 ? s3 : null;
            }
            if (targetModelCode == null || targetModelCode.isBlank()) continue;

            // Collect unique reference IDs
            Set<String> refIds = new java.util.LinkedHashSet<>();
            for (Map<String, Object> record : records) {
                Object val = record.get(columnName);
                if (val != null && !String.valueOf(val).isBlank()) {
                    refIds.add(String.valueOf(val));
                }
            }
            if (refIds.isEmpty()) continue;

            // Batch lookup: query target model for display values
            try {
                Optional<ModelDefinition> targetModelOpt = metadataService.getModelDefinition(targetModelCode);
                String targetTable = targetModelOpt
                        .map(ModelDefinition::getTableName)
                        .orElse(resolveSystemTable(targetModelCode));
                if (targetTable == null) continue;

                String inClause = refIds.stream()
                        .map(id -> "'" + id.replace("'", "''") + "'")
                        .collect(java.util.stream.Collectors.joining(","));

                // Resolve display column name from field code or target model display field metadata.
                // For system tables (no ModelDefinition), use SYSTEM_TABLE_DISPLAY_EXPRESSIONS mapping
                // which includes COALESCE fallbacks for when primary display columns are empty.
                String displayColumnExpr;
                String displayColumnName;
                if (targetModelOpt.isEmpty() && displayField == null && SYSTEM_TABLE_DISPLAY_EXPRESSIONS.containsKey(targetModelCode)) {
                    displayColumnExpr = SYSTEM_TABLE_DISPLAY_EXPRESSIONS.get(targetModelCode);
                    displayColumnName = "display_value";
                } else {
                    displayColumnName = resolveReferenceDisplayColumn(targetModelOpt.orElse(null), displayField);
                    displayColumnExpr = displayColumnName;
                }

                String sql = "SELECT pid, " + displayColumnExpr + " AS " + displayColumnName
                        + " FROM " + targetTable
                        + " WHERE pid IN (" + inClause + ")"
                        + buildSoftDeleteClause(targetModelOpt.orElse(null));

                List<Map<String, Object>> targetRows = dynamicDataMapper.selectByQuery(sql, java.util.Collections.emptyMap());
                Map<String, String> displayMap = new java.util.HashMap<>();
                for (Map<String, Object> row : targetRows) {
                    String pid = String.valueOf(row.get("pid"));
                    Object dispVal = row.get(displayColumnName);
                    if (dispVal != null) {
                        displayMap.put(pid, String.valueOf(dispVal));
                    }
                }

                // Populate _display suffix
                String displayKey = fieldCode + "_display";
                for (Map<String, Object> record : records) {
                    Object val = record.get(columnName);
                    if (val != null) {
                        String display = displayMap.get(String.valueOf(val));
                        if (display != null) {
                            record.put(displayKey, display);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to enrich REFERENCE display for field {} in model {}: {}",
                        fieldCode, modelCode, e.getMessage());
            }
        }
    }

    private static final Map<String, String> SYSTEM_TABLE_MAP = Map.of(
            "ns_user", "ab_user",
            "ab_user", "ab_user"
    );

    private String resolveSystemTable(String modelCode) {
        return SYSTEM_TABLE_MAP.get(modelCode);
    }

    private String buildSoftDeleteClause(ModelDefinition modelDefinition) {
        if (modelDefinition != null && modelDefinition.isSoftDelete()) {
            return " AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        }
        return "";
    }

    private String resolveReferenceDisplayColumn(ModelDefinition targetModel, String configuredDisplayField) {
        List<FieldDefinition> fields = targetModel != null && targetModel.getFields() != null
                ? targetModel.getFields()
                : java.util.Collections.emptyList();

        if (configuredDisplayField != null && !configuredDisplayField.isBlank()) {
            for (FieldDefinition field : fields) {
                if (configuredDisplayField.equals(field.getCode())) {
                    return field.getColumnName() != null ? field.getColumnName() : field.getCode();
                }
            }
            return configuredDisplayField;
        }

        if (targetModel != null) {
            for (FieldDefinition field : metadataService.getDisplayFields(targetModel.getCode())) {
                if (!field.isPrimaryKey()) {
                    return field.getColumnName() != null ? field.getColumnName() : field.getCode();
                }
            }
        }

        for (FieldDefinition field : fields) {
            String columnName = field.getColumnName() != null ? field.getColumnName() : field.getCode();
            String normalized = columnName.toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("_name") || "name".equals(normalized) || normalized.endsWith("_title")
                    || "title".equals(normalized) || normalized.endsWith("_code") || "code".equals(normalized)) {
                return columnName;
            }
        }

        return "pid";
    }

    /**
     * Display column expression mapping for system tables that don't have ModelDefinition registered.
     * Uses COALESCE to fall back through multiple columns (e.g., nick_name → user_name → email).
     * Aliased as 'display_value' in the SELECT clause.
     */
    private static final Map<String, String> SYSTEM_TABLE_DISPLAY_EXPRESSIONS = Map.of(
            "ab_user", "COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email)",
            "ns_user", "COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email)"
    );

    @Override
    public PaginationResult<Map<String, Object>> listByQueryCode(String queryCode, DynamicQueryRequest request) {
        log.info("List by NamedQuery data source: queryCode={}", queryCode);
        return listFromNamedQuery(queryCode, request);
    }

    private String resolveViewNamedQueryCode(String modelCode) {
        var modelEntity = metaModelMapper.findCurrentByCode(modelCode);
        if (modelEntity == null) {
            return modelCode;
        }
        Object namedQuery = modelEntity.getExtension() != null
                ? modelEntity.getExtension().get("namedQuery")
                : null;
        if (namedQuery == null) {
            return modelCode;
        }
        String code = namedQuery.toString().trim();
        return code.isEmpty() ? modelCode : code;
    }

    /**
     * List data by delegating to NamedQuery with the given code.
     * Passes through filter conditions and sort fields from the dynamic query request.
     */
    private PaginationResult<Map<String, Object>> listFromNamedQuery(String queryCode, DynamicQueryRequest request) {
        log.debug("NamedQuery list: code={}", queryCode);
        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        nqRequest.setPage(request.getPageNum());
        nqRequest.setSize(request.getPageSize());
        nqRequest.setExecuteQuery(true);

        ObjectMapper mapper = new ObjectMapper();

        // Pass through filter conditions
        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            var whereArray = mapper.createArrayNode();
            for (QueryCondition cond : request.getConditions()) {
                var node = mapper.createObjectNode();
                node.put("field", cond.getFieldName());
                node.put("operator", cond.getOperator().name().toLowerCase());
                if (cond.getOperator() == QueryCondition.Operator.IN || cond.getOperator() == QueryCondition.Operator.NOT_IN) {
                    node.set("value", mapper.valueToTree(cond.getValues() != null ? cond.getValues() : List.of()));
                } else if (cond.getOperator() == QueryCondition.Operator.BETWEEN) {
                    node.set("value", mapper.valueToTree(cond.getValues() != null ? cond.getValues() : List.of()));
                } else {
                    node.set("value", mapper.valueToTree(cond.getValue()));
                }
                whereArray.add(node);
            }
            nqRequest.setWhereConditions(whereArray);
        }

        // Pass through sort fields
        if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
            var orderArray = mapper.createArrayNode();
            for (SortField sf : request.getSortFields()) {
                var node = mapper.createObjectNode();
                node.put("field", sf.getFieldName());
                node.put("direction", sf.getDirection().name());
                orderArray.add(node);
            }
            nqRequest.setOrderConditions(orderArray);
        }

        return namedQueryService.executeQuery(queryCode, nqRequest);
    }

    @Override
    @Observed(name = "dynamic_data.get_by_id", contextualName = "dynamic-data-get-by-id")
    public Map<String, Object> getById(String modelCode, String recordId) {
        validateModelCode(modelCode);
        if (recordId == null || recordId.trim().isEmpty()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }
        
        logOperation("getById", modelCode, recordId);

        ModelDefinition model = getModelDefinition(modelCode);

        // Phase 1 virtual-model dispatch: delegate to executor if non-physical sourceType
        // has a registered executor; otherwise fall through to inline physical path.
        Optional<ModelDataExecutor> executorOpt = executorRegistry.resolve(model.getSourceType());
        if (executorOpt.isPresent()) {
            Map<String, Object> record = executorOpt.get().get(modelCode, recordId);
            if (record == null) {
                throw new MetaServiceException("Record not found: " + recordId + " in model: " + modelCode);
            }
            return record;
        }

        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        Long tenantId = getCurrentTenantId();
        
        // 构建查询条件
        List<QueryCondition> conditions = new ArrayList<>();
        conditions.add(QueryCondition.builder()
                .fieldName(primaryKey.getCode())
                .operator(QueryCondition.Operator.EQ)
                .value(recordId)
                .build());
        conditions.add(QueryCondition.builder()
                .fieldName("tenant_id")
                .operator(QueryCondition.Operator.EQ)
                .value(tenantId)
                .build());
        
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(model, conditions);

        String sql = queryBuilder.getSql();
        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        List<Map<String, Object>> records = dynamicDataMapper.selectByQuery(sql, paramMap);
        
        if (records.isEmpty()) {
            throw new MetaServiceException("Record not found: " + recordId + " in model: " + modelCode);
        }

        Map<String, Object> record = records.get(0);

        // Apply row-level access check for single record
        try {
            Long userId = getCurrentUserId();
            if (!dataPermissionEngine.canAccessRecord(tenantId, modelCode, userId, record)) {
                throw new MetaServiceException("Access denied: you do not have permission to view this record");
            }
        } catch (MetaServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to check row-level access for model {} record {}: {}", modelCode, recordId, e.getMessage());
        }

        // Apply column-level field masking (policy-based)
        try {
            Long userId = getCurrentUserId();
            List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
            if (maskRules != null && !maskRules.isEmpty()) {
                List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(List.of(record), maskRules);
                if (masked != null && !masked.isEmpty()) {
                    record = masked.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply field masking for model {} record {}: {}", modelCode, recordId, e.getMessage());
        }

        // Apply configurable field masking for detail view (A9)
        try {
            Long userId = getCurrentUserId();
            record = fieldMaskService.applyMaskingForDetail(modelCode, record, userId);
        } catch (Exception e) {
            log.warn("Failed to apply configurable field masking for model {} record {}: {}", modelCode, recordId, e.getMessage());
        }

        // Apply field-level permission filtering — remove hidden fields
        record = applyFieldPermissionFilterSingle(modelCode, record);

        return record;
    }

    @Override
    @Transactional
    public Map<String, Object> create(String modelCode, Map<String, Object> data) {
        validateModelCode(modelCode);
        if (data == null || data.isEmpty()) {
            throw new MetaServiceException("Data cannot be null or empty");
        }
        
        logOperation("create", modelCode, data.keySet());
        
        ModelDefinition model = getModelDefinition(modelCode);
        
        // 检查表是否存在，如果不存在则自动创建
        ensureTableExists(modelCode);
        
        // Normalize temporal string values to typed objects (LocalDate/Instant) before validation
        payloadTemporalNormalizer.normalize(data, model);
        // 使用验证服务的严格模式进行验证
        // 验证失败会抛出异常并触发事务回滚
        validationService.validateAndThrow(model, data, ValidationContext.CREATE);
        
        // 设置系统字段
        Map<String, Object> enrichedData = new HashMap<>(data);
        enrichedData.put("created_at", java.time.Instant.now());
        enrichedData.put("created_by", getCurrentUserId());
        enrichedData.put("updated_at", java.time.Instant.now());
        enrichedData.put("updated_by", getCurrentUserId());
        enrichedData.put("tenant_id", getCurrentTenantId());
        
        // 生成主键（如果需要）
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        if (!enrichedData.containsKey(primaryKey.getCode())) {
            // 使用TypeSystemManager根据字段类型生成主键
            Object generatedPk = typeSystemManager.generatePrimaryKey(primaryKey);
            enrichedData.put(primaryKey.getCode(), generatedPk);
            log.debug("Generated primary key for model {}: {} = {}", 
                     modelCode, primaryKey.getCode(), generatedPk);
        }
        
        // 数据类型转换
        enrichedData = convertDataTypes(model, enrichedData);

        // Filter out non-writable virtual fields (COMPUTED_READONLY, TRANSIENT)
        List<String> changedFields = new ArrayList<>(enrichedData.keySet());
        filterVirtualFields(model, enrichedData);

        Map<String, Object> columnData = toColumnData(model, enrichedData);

        // Identify JSONB host columns for SQL generation
        Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);

        // 执行插入
        int result = jsonbColumns.isEmpty()
                ? dynamicDataMapper.insert(model.getTableName(), columnData)
                : dynamicDataMapper.insertWithJsonb(model.getTableName(), columnData, jsonbColumns);
        if (result <= 0) {
            throw new MetaServiceException("Failed to create record");
        }

        // Materialize computed fields after insert
        String recordIdValue = enrichedData.get(primaryKey.getCode()).toString();
        virtualFieldEngine.materialize(modelCode, recordIdValue, changedFields);

        // Record change log
        Map<String, Object> createdRecord = getById(modelCode, recordIdValue);
        try {
            List<FieldChange> changes = changeTracker.diff(null, createdRecord, modelCode);
            changeTracker.recordChange(ChangeRecord.builder()
                    .modelCode(modelCode)
                    .recordId(recordIdValue)
                    .operation("create")
                    .changedBy(getCurrentUserId())
                    .changes(changes)
                    .snapshotAfter(createdRecord)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record change log for create: model={}, id={}: {}",
                    modelCode, recordIdValue, e.getMessage());
        }

        // Trigger automations for record creation
        try {
            getAutomationTriggerService().onRecordCreate(modelCode, recordIdValue, createdRecord);
        } catch (Exception e) {
            log.error("Failed to trigger automations for create: model={}, id={}: {}",
                    modelCode, recordIdValue, e.getMessage());
        }

        return createdRecord;
    }

    /**
     * 数据类型转换
     */
    private Map<String, Object> convertDataTypes(ModelDefinition model, Map<String, Object> data) {
        Map<String, Object> convertedData = new HashMap<>(data);
        
        for (FieldDefinition field : model.getFields()) {
            // Skip JSONB virtual fields — they are merged and serialized separately
            if (field.isJsonbVirtual()) continue;

            String fieldCode = field.getCode();
            Object value = convertedData.get(fieldCode);

            if (value == null) {
                continue;
            }

            try {
                Object convertedValue = convertFieldValue(field, value);
                convertedData.put(fieldCode, convertedValue);
            } catch (Exception e) {
                log.warn("Failed to convert field {} value {}: {}", fieldCode, value, e.getMessage());
                // 保持原值，让数据库处理类型转换
            }
        }
        
        return convertedData;
    }

    /**
     * Filter out non-writable virtual fields (COMPUTED_READONLY, TRANSIENT) from data map
     */
    private void filterVirtualFields(ModelDefinition model, Map<String, Object> data) {
        if (model.getFields() == null) {
            return;
        }
        Set<String> virtualFieldCodes = model.getFields().stream()
                .filter(f -> f.isComputedReadonly() || f.isTransientField())
                .map(FieldDefinition::getCode)
                .collect(Collectors.toSet());

        data.keySet().removeAll(virtualFieldCodes);
    }

    /**
     * 转换单个字段值
     */
    private Object convertFieldValue(FieldDefinition field, Object value) {
        if (value == null) {
            return null;
        }
        
        String dataType = field.getDataType();
        if (dataType == null) {
            return value;
        }
        
        switch (dataType.toUpperCase()) {
            case "DATE":
                if (value instanceof String) {
                    try {
                        return java.sql.Date.valueOf((String) value);
                    } catch (Exception e) {
                        throw new MetaServiceException(
                            "Invalid date value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "DATETIME":
            case "TIMESTAMP":
                if (value instanceof String) {
                    try {
                        return java.sql.Timestamp.valueOf((String) value);
                    } catch (Exception e) {
                        throw new MetaServiceException(
                            "Invalid datetime value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "INTEGER":
                if (value instanceof String) {
                    try {
                        return Integer.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid integer value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "LONG":
                if (value instanceof String) {
                    try {
                        return Long.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid long value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "DECIMAL":
                if (value instanceof String) {
                    try {
                        return new java.math.BigDecimal((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid decimal value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;
                
            case "BOOLEAN":
                if (value instanceof String) {
                    return Boolean.valueOf((String) value);
                }
                return value;
                
            default:
                return value;
        }
    }

    /**
     * 确保模型对应的表存在，如果不存在则自动创建
     */
    private void ensureTableExists(String modelCode) {

            ModelDefinition model = getModelDefinition(modelCode);
            String tableName = model.getTableName();
            
            // 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                log.info("Table {} does not exist, creating it automatically for model: {}", tableName, modelCode);
                
                // 使用SchemaManagementService创建表
                SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
                if (!result.isSuccess()) {
                    throw new MetaServiceException("Failed to create table for model " + modelCode + ": " + result.getMessage());
                }
                
                log.info("Successfully created table {} for model: {}", tableName, modelCode);
            }

    }

    @Override
    @Transactional
    public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
        validateModelCode(modelCode);
        if (recordId == null || recordId.trim().isEmpty()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }
        if (data == null || data.isEmpty()) {
            throw new MetaServiceException("Data cannot be null or empty");
        }
        
        logOperation("update", modelCode, recordId, data.keySet());
        
        try {
            ModelDefinition model = getModelDefinition(modelCode);
            
            // Check if record exists
            Map<String, Object> existingRecord = getById(modelCode, recordId);
            if (existingRecord == null) {
                throw new MetaServiceException("Record not found with ID: " + recordId);
            }
            
            // Normalize temporal string values to typed objects (LocalDate/Instant) before validation
            payloadTemporalNormalizer.normalize(data, model);
            // 使用验证服务的严格模式进行验证
            // 验证失败会抛出异常并触发事务回滚
            validationService.validateAndThrow(model, data, ValidationContext.UPDATE);
            
            // Set system fields
            Map<String, Object> enrichedData = new HashMap<>(data);
            enrichedData.put("updated_at", java.time.Instant.now());
            enrichedData.put("updated_by", getCurrentUserId());
            enrichedData.remove("tenant_id");
            enrichedData.remove("created_at");
            enrichedData.remove("created_by");
            
            // Remove primary key field (not allowed to update)
            try {
                FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
                if (primaryKey != null) {
                    enrichedData.remove(primaryKey.getCode());
                }
            } catch (Exception e) {
                log.warn("Could not get primary key field for model {}: {}", modelCode, e.getMessage());
                // Try common primary key field names
                enrichedData.remove("id");
                enrichedData.remove(modelCode + "_id");
                enrichedData.remove("device_id"); // For device model specifically
            }
            
            // Filter out non-writable virtual fields (COMPUTED_READONLY, TRANSIENT)
            List<String> changedFields = new ArrayList<>(enrichedData.keySet());
            filterVirtualFields(model, enrichedData);

            // Optimistic locking: extract expectedVersion if present
            Object expectedVersion = enrichedData.remove("_expectedVersion");

            // Use JSONB-merge-aware toColumnData for UPDATE to preserve unmodified JSONB keys
            Map<String, Object> columnData = toColumnDataForUpdate(model, enrichedData, existingRecord);
            FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
            Long tenantId = getCurrentTenantId();
            String primaryKeyColumn = primaryKey.getColumnName();

            // Build update conditions
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(primaryKeyColumn, recordId);
            conditions.put("tenant_id", tenantId);

            // If expectedVersion provided, add optimistic lock condition
            if (expectedVersion != null) {
                conditions.put("row_version", expectedVersion);
                columnData.put("row_version", ((Number) expectedVersion).intValue() + 1);
            }

            // Execute update with JSONB awareness
            Set<String> jsonbColumns = JsonbFieldHelper.getJsonbHostColumns(model);
            int result = jsonbColumns.isEmpty()
                    ? dynamicDataMapper.update(model.getTableName(), columnData, conditions)
                    : dynamicDataMapper.updateWithJsonb(model.getTableName(), columnData, conditions, jsonbColumns);
            if (result <= 0) {
                if (expectedVersion != null) {
                    throw new MetaServiceException("Update failed: version conflict (expected version " + expectedVersion + ")");
                }
                throw new MetaServiceException("Failed to update record");
            }

            // Materialize computed fields after update
            virtualFieldEngine.materialize(modelCode, recordId, changedFields);

            // Record change log
            Map<String, Object> updatedRecord = getById(modelCode, recordId);
            try {
                List<FieldChange> changes = changeTracker.diff(existingRecord, updatedRecord, modelCode);
                if (!changes.isEmpty()) {
                    changeTracker.recordChange(ChangeRecord.builder()
                            .modelCode(modelCode)
                            .recordId(recordId)
                            .operation("update")
                            .changedBy(getCurrentUserId())
                            .changes(changes)
                            .snapshotBefore(existingRecord)
                            .snapshotAfter(updatedRecord)
                            .build());
                }
            } catch (Exception e) {
                log.error("Failed to record change log for update: model={}, id={}: {}",
                        modelCode, recordId, e.getMessage());
            }

            // Trigger automations for record update
            try {
                getAutomationTriggerService().onRecordUpdate(modelCode, recordId, existingRecord, updatedRecord);
            } catch (Exception e) {
                log.error("Failed to trigger automations for update: model={}, id={}: {}",
                        modelCode, recordId, e.getMessage());
            }

            return updatedRecord;
            
        } catch (Exception e) {
            log.error("Update operation failed for model {} with ID {}: {}", modelCode, recordId, e.getMessage(), e);
            throw new MetaServiceException("Update failed: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void delete(String modelCode, String recordId) {
        validateModelCode(modelCode);
        if (recordId == null || recordId.trim().isEmpty()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }

        logOperation("delete", modelCode, recordId);

        ModelDefinition model = getModelDefinition(modelCode);

        // Get record before deletion for change tracking
        Map<String, Object> existingRecord = getById(modelCode, recordId);

        // 构建删除条件
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        Map<String, Object> conditions = new java.util.HashMap<>();
        conditions.put(primaryKey.getColumnName(), recordId);
        conditions.put("tenant_id", getCurrentTenantId());

        int result;
        if (model.isSoftDelete()) {
            // Soft delete: UPDATE deleted_flag = true
            Map<String, Object> updateData = new java.util.HashMap<>();
            updateData.put("deleted_flag", true);
            updateData.put("updated_at", java.time.Instant.now());
            updateData.put("updated_by", getCurrentUserId());
            result = dynamicDataMapper.update(model.getTableName(), updateData, conditions);
        } else {
            // Hard delete: DELETE FROM (default behavior)
            result = dynamicDataMapper.delete(model.getTableName(), conditions);
        }
        if (result <= 0) {
            throw new MetaServiceException("Failed to delete record");
        }

        // Record change log
        try {
            List<FieldChange> changes = changeTracker.diff(existingRecord, null, modelCode);
            changeTracker.recordChange(ChangeRecord.builder()
                    .modelCode(modelCode)
                    .recordId(recordId)
                    .operation("delete")
                    .changedBy(getCurrentUserId())
                    .changes(changes)
                    .snapshotBefore(existingRecord)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record change log for delete: model={}, id={}: {}",
                    modelCode, recordId, e.getMessage());
        }
    }

    @Override
    public DynamicBatchResponse batchCreate(String modelCode, List<Map<String, Object>> dataList) {
        validateModelCode(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }

        logOperation("batchCreate", modelCode, dataList.size());

        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);

        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(dataList.size());

        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        // No outer @Transactional — each create() runs in its own transaction
        for (int i = 0; i < dataList.size(); i++) {
            try {
                Map<String, Object> data = dataList.get(i);

                // Check if record already exists (idempotent behavior)
                Object primaryKeyValue = data.get(primaryKey.getCode());
                if (primaryKeyValue != null) {
                    try {
                        Map<String, Object> existingRecord = getById(modelCode, primaryKeyValue.toString());
                        if (existingRecord != null) {
                            log.info("Record with primary key {} already exists, skipping creation", primaryKeyValue);
                            successCount++;
                            continue;
                        }
                    } catch (Exception e) {
                        // Record doesn't exist, proceed with creation
                    }
                }

                create(modelCode, data);
                successCount++;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Reliable duplicate key detection via exception type, not string matching
                log.info("Duplicate key detected for row {}, treating as success", i + 1);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                log.warn("Batch create failed for row {}: {}", i + 1, e.getMessage());
            }
        }

        response.setSuccess(successCount);
        response.setFailed(failedCount);
        response.setErrors(errors);

        return response;
    }

    @Override
    @Transactional
    public DynamicBatchResponse batchUpdate(String modelCode, List<Map<String, Object>> dataList) {
        validateModelCode(modelCode);
        if (dataList == null || dataList.isEmpty()) {
            throw new MetaServiceException("Data list cannot be null or empty");
        }
        
        logOperation("batchUpdate", modelCode, dataList.size());
        
        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        
        DynamicBatchResponse response = new DynamicBatchResponse();
        response.setTotal(dataList.size());
        
        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();
        
        for (int i = 0; i < dataList.size(); i++) {
            try {
                Map<String, Object> data = dataList.get(i);
                Object recordId = data.get(primaryKey.getCode());
                if (recordId == null) {
                    throw new MetaServiceException("Primary key is required for update");
                }
                
                update(modelCode, recordId.toString(), data);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                log.warn("Batch update failed for row {}: {}", i + 1, e.getMessage());
            }
        }
        
        response.setSuccess(successCount);
        response.setFailed(failedCount);
        response.setErrors(errors);
        
        return response;
    }

    @Override
    @Transactional
    public void batchDelete(String modelCode, List<String> recordIds) {
        validateModelCode(modelCode);
        if (recordIds == null || recordIds.isEmpty()) {
            throw new MetaServiceException("Record IDs cannot be null or empty");
        }
        
        logOperation("batchDelete", modelCode, recordIds.size());
        
        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        
        // 构建批量删除条件
        List<QueryCondition> conditions = List.of(
                QueryCondition.builder()
                        .fieldName(primaryKey.getCode())
                        .operator(QueryCondition.Operator.IN)
                        .value(recordIds)
                        .build()
        );
        
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(model, conditions);
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), getCurrentTenantId());
        
        // 使用SecureSqlRewriter替代正则表达式重写SQL
        String sql = secureSqlRewriter.rewriteForDelete(queryBuilder.getSql(), model.getTableName());
        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        
        int result = dynamicDataMapper.deleteByQuery(sql, paramMap);
        
        log.info("Batch deleted {} records from model: {}", result, modelCode);
    }

    // ==================== Custom Query ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> executeCustomQuery(String modelCode, String queryName, Map<String, Object> queryParams) {
        validateModelCode(modelCode);
        logOperation("executeCustomQuery", modelCode, queryName);

        NamedQueryTestRequest testRequest = new NamedQueryTestRequest();
        testRequest.setParameters(queryParams != null ? queryParams : Collections.emptyMap());
        testRequest.setSize(1000);
        testRequest.setPage(1);

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryName, testRequest);
        return result.getRecords() != null ? result.getRecords() : Collections.emptyList();
    }

    // ==================== Aggregate ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> aggregate(String modelCode, AggregateRequest aggregateRequest) {
        validateModelCode(modelCode);
        logOperation("aggregate", modelCode, aggregateRequest);

        ModelDefinition model = getModelDefinition(modelCode);

        // Build aggregate query
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildAggregateQuery(model, aggregateRequest);

        // Add conditions if present
        if (aggregateRequest.getConditions() != null) {
            for (QueryCondition condition : aggregateRequest.getConditions()) {
                queryBuilder.addCondition(condition.getFieldName(), condition.getOperator().name(), condition.getValue());
            }
        }

        // Add tenant isolation
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        // Row-level permission filter (fail-secure)
        try {
            String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission in aggregate for model {} — denying access", modelCode, e);
            throw new MetaServiceException("Data permission evaluation failed for aggregate: " + modelCode);
        }

        // Domain isolation filter (fail-secure)
        try {
            String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply domain filter in aggregate for model {} — denying access", modelCode, e);
            throw new MetaServiceException("Data domain filter evaluation failed for aggregate: " + modelCode);
        }

        // Add GROUP BY if present
        String sql = queryBuilder.getSql();
        if (aggregateRequest.getGroupByFields() != null && !aggregateRequest.getGroupByFields().isEmpty()) {
            List<String> groupColumns = aggregateRequest.getGroupByFields().stream()
                    .map(f -> resolveColumnName(model, f))
                    .collect(Collectors.toList());
            sql = sql + " GROUP BY " + String.join(", ", groupColumns);
        }

        // Add limit
        if (aggregateRequest.getLimit() != null && aggregateRequest.getLimit() > 0) {
            sql = sql + " LIMIT " + aggregateRequest.getLimit();
        }

        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, paramMap);

        if (results == null || results.isEmpty()) {
            return Collections.emptyMap();
        }

        // If no GROUP BY, return the single aggregate row
        if (aggregateRequest.getGroupByFields() == null || aggregateRequest.getGroupByFields().isEmpty()) {
            return results.get(0);
        }

        // With GROUP BY, return all results in a wrapper
        Map<String, Object> response = new HashMap<>();
        response.put("groups", results);
        response.put("groupCount", results.size());
        return response;
    }

    // ==================== Stats ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(String modelCode, Map<String, Object> statsParams) {
        validateModelCode(modelCode);
        logOperation("getStats", modelCode, statsParams);

        // Parse stats params
        @SuppressWarnings("unchecked")
        List<String> fields = statsParams != null ? (List<String>) statsParams.get("fields") : null;
        @SuppressWarnings("unchecked")
        List<String> functions = statsParams != null ? (List<String>) statsParams.get("functions") : null;

        // Build AggregateRequest
        List<AggregateRequest.AggregateField> aggregateFields = new ArrayList<>();

        // Default: count all records
        aggregateFields.add(AggregateRequest.AggregateField.builder()
                .fieldName("*")
                .function(AggregateRequest.AggregateFunction.COUNT)
                .alias("total_count")
                .build());

        // Add requested field/function combinations
        if (fields != null && functions != null) {
            for (String field : fields) {
                for (String function : functions) {
                    String alias = function.toLowerCase() + "_" + field;
                    AggregateRequest.AggregateFunction aggFunc =
                            AggregateRequest.AggregateFunction.valueOf(function.toUpperCase());
                    aggregateFields.add(AggregateRequest.AggregateField.builder()
                            .fieldName(field)
                            .function(aggFunc)
                            .alias(alias)
                            .build());
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<String> groupByFields = statsParams != null ? (List<String>) statsParams.get("groupBy") : null;

        AggregateRequest aggregateRequest = AggregateRequest.builder()
                .aggregateFields(aggregateFields)
                .groupByFields(groupByFields)
                .build();

        return aggregate(modelCode, aggregateRequest);
    }

    // ==================== Relation Data ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRelationData(String modelCode, String recordId, String relationName, Map<String, Object> queryParams) {
        validateModelCode(modelCode);
        logOperation("getRelationData", modelCode, relationName);

        ModelDefinition model = getModelDefinition(modelCode);
        RelationDefinition relation = findRelation(model, relationName);

        Long tenantId = getCurrentTenantId();

        // Security: validate all relation SQL identifiers to prevent injection
        java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
        if (relation.getTargetTable() != null && !NAME_PATTERN.matcher(relation.getTargetTable()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation target table: " + relation.getTargetTable());
        }
        if (relation.getTargetField() != null && !NAME_PATTERN.matcher(relation.getTargetField()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation target field: " + relation.getTargetField());
        }
        if (relation.getSourceField() != null && !NAME_PATTERN.matcher(relation.getSourceField()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation source field: " + relation.getSourceField());
        }
        if (relation.getJoinTable() != null && !NAME_PATTERN.matcher(relation.getJoinTable()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation join table: " + relation.getJoinTable());
        }

        if (relation.getRelationType() == RelationDefinition.RelationType.MANY_TO_MANY) {
            // Many-to-many: query join table first, then target table
            String joinSql = "SELECT " + relation.getTargetField() + " FROM " + relation.getJoinTable()
                    + " WHERE " + relation.getSourceField() + " = #{params.recordId}"
                    + " AND tenant_id = #{params.tenantId}";
            Map<String, Object> joinParams = new HashMap<>();
            joinParams.put("recordId", recordId);
            joinParams.put("tenantId", tenantId);

            List<Map<String, Object>> joinResults = dynamicDataMapper.selectByQuery(joinSql, joinParams);
            if (joinResults.isEmpty()) {
                return Collections.emptyList();
            }

            // Extract target IDs
            List<Object> targetIds = joinResults.stream()
                    .map(row -> row.get(relation.getTargetField()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (targetIds.isEmpty()) {
                return Collections.emptyList();
            }

            // Query target table — use parameterized IN clause to prevent SQL injection
            Map<String, Object> targetParams = new HashMap<>();
            targetParams.put("tenantId", tenantId);

            StringBuilder inPlaceholders = new StringBuilder();
            for (int i = 0; i < targetIds.size(); i++) {
                if (i > 0) inPlaceholders.append(",");
                String paramKey = "id_" + i;
                inPlaceholders.append("#{params.").append(paramKey).append("}");
                targetParams.put(paramKey, targetIds.get(i));
            }

            StringBuilder targetSqlBuilder = new StringBuilder();
            targetSqlBuilder.append("SELECT * FROM ").append(relation.getTargetTable())
                    .append(" WHERE id IN (").append(inPlaceholders).append(")")
                    .append(" AND tenant_id = #{params.tenantId}");

            // Row-level permission filter on target model (fail-secure)
            String targetModelCode = relation.getTargetModel();
            Long userId = getCurrentUserId();
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    targetSqlBuilder.append(" ").append(rowFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply row-level permission in getRelationData for target: {} — denying access", targetModelCode, e);
                throw new MetaServiceException("Data permission evaluation failed for relation query");
            }

            // Domain isolation filter on target model (fail-secure)
            try {
                String domainFilter = dataDomainService.buildDomainFilter(targetModelCode, userId);
                if (domainFilter != null && !domainFilter.isBlank()) {
                    targetSqlBuilder.append(" ").append(domainFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply domain filter in getRelationData for target: {} — denying access", targetModelCode, e);
                throw new MetaServiceException("Data domain filter failed for relation query");
            }

            List<Map<String, Object>> targetResults = dynamicDataMapper.selectByQuery(targetSqlBuilder.toString(), targetParams);

            // Column masking on target model results (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    targetResults = dataPermissionEngine.applyFieldMasking(targetResults, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking in getRelationData for target: {} — denying access", targetModelCode, e);
                throw new MetaServiceException("Field masking failed for relation query");
            }

            return targetResults;
        } else {
            // One-to-many / Many-to-one: direct query on target table
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT * FROM ").append(relation.getTargetTable())
                    .append(" WHERE ").append(relation.getTargetField()).append(" = #{params.recordId}")
                    .append(" AND tenant_id = #{params.tenantId}");
            Map<String, Object> params = new HashMap<>();
            params.put("recordId", recordId);
            params.put("tenantId", tenantId);

            // Row-level permission filter on target model (fail-secure)
            String targetModelCode = relation.getTargetModel();
            Long userId = getCurrentUserId();
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    sqlBuilder.append(" ").append(rowFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply row-level permission in getRelationData for target: {} — denying access", targetModelCode, e);
                throw new MetaServiceException("Data permission evaluation failed for relation query");
            }

            // Domain isolation filter on target model (fail-secure)
            try {
                String domainFilter = dataDomainService.buildDomainFilter(targetModelCode, userId);
                if (domainFilter != null && !domainFilter.isBlank()) {
                    sqlBuilder.append(" ").append(domainFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply domain filter in getRelationData for target: {} — denying access", targetModelCode, e);
                throw new MetaServiceException("Data domain filter failed for relation query");
            }

            // Apply limit from queryParams
            if (queryParams != null && queryParams.containsKey("limit")) {
                sqlBuilder.append(" LIMIT ").append(Integer.parseInt(queryParams.get("limit").toString()));
            }

            List<Map<String, Object>> relationResults = dynamicDataMapper.selectByQuery(sqlBuilder.toString(), params);

            // Column masking on target model results (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    relationResults = dataPermissionEngine.applyFieldMasking(relationResults, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking in getRelationData for target: {} — denying access", targetModelCode, e);
                throw new MetaServiceException("Field masking failed for relation query");
            }

            return relationResults;
        }
    }

    // ==================== Relation CRUD ====================

    @Override
    @Transactional
    public RelationOperationResult createRelations(String modelCode, String recordId, String relationName, List<String> targetRecordIds) {
        validateModelCode(modelCode);
        logOperation("createRelations", modelCode, relationName);

        ModelDefinition model = getModelDefinition(modelCode);
        RelationDefinition relation = findRelation(model, relationName);

        if (relation.getRelationType() != RelationDefinition.RelationType.MANY_TO_MANY) {
            throw new MetaServiceException("createRelations only supports MANY_TO_MANY relations. Use update for other types.");
        }

        Long tenantId = getCurrentTenantId();
        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String targetId : targetRecordIds) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put(relation.getSourceField(), recordId);
                data.put(relation.getTargetField(), targetId);
                data.put("tenant_id", tenantId);
                data.put("created_at", java.time.Instant.now());

                dynamicDataMapper.insert(relation.getJoinTable(), data);
                successIds.add(targetId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Relation already exists — treat as success (idempotent)
                log.debug("Relation already exists for target {}, treating as success", targetId);
                successIds.add(targetId);
            } catch (Exception e) {
                log.warn("Failed to create relation for target {}: {}", targetId, e.getMessage());
                failedIds.add(targetId);
            }
        }

        boolean allSuccess = failedIds.isEmpty();
        return RelationOperationResult.builder()
                .success(allSuccess)
                .operationType(RelationOperationResult.OperationType.CREATE_RELATION)
                .successCount(successIds.size())
                .failedCount(failedIds.size())
                .successRecordIds(successIds)
                .failedRecordIds(failedIds)
                .errorMessage(allSuccess ? null : "Some relations failed to create")
                .build();
    }

    @Override
    @Transactional
    public RelationOperationResult removeRelations(String modelCode, String recordId, String relationName, List<String> targetRecordIds) {
        validateModelCode(modelCode);
        logOperation("removeRelations", modelCode, relationName);

        ModelDefinition model = getModelDefinition(modelCode);
        RelationDefinition relation = findRelation(model, relationName);

        if (relation.getRelationType() != RelationDefinition.RelationType.MANY_TO_MANY) {
            throw new MetaServiceException("removeRelations only supports MANY_TO_MANY relations.");
        }

        Long tenantId = getCurrentTenantId();
        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String targetId : targetRecordIds) {
            try {
                Map<String, Object> conditions = new HashMap<>();
                conditions.put(relation.getSourceField(), recordId);
                conditions.put(relation.getTargetField(), targetId);
                conditions.put("tenant_id", tenantId);

                int deleted = dynamicDataMapper.delete(relation.getJoinTable(), conditions);
                if (deleted > 0) {
                    successIds.add(targetId);
                } else {
                    failedIds.add(targetId);
                }
            } catch (Exception e) {
                log.warn("Failed to remove relation for target {}: {}", targetId, e.getMessage());
                failedIds.add(targetId);
            }
        }

        boolean allSuccess = failedIds.isEmpty();
        return RelationOperationResult.builder()
                .success(allSuccess)
                .operationType(RelationOperationResult.OperationType.REMOVE_RELATION)
                .successCount(successIds.size())
                .failedCount(failedIds.size())
                .successRecordIds(successIds)
                .failedRecordIds(failedIds)
                .errorMessage(allSuccess ? null : "Some relations failed to remove")
                .build();
    }

    // ==================== Validation ====================

    @Override
    public ValidationResult validate(String modelCode, Map<String, Object> data, ValidationContext validationContext) {
        validateModelCode(modelCode);

        ModelDefinition model = getModelDefinition(modelCode);
        return validationService.validateData(model, data, validationContext);
    }

    // ==================== Field Options ====================

    @Override
    @Transactional(readOnly = true)
    public List<FieldOption> getFieldOptions(String modelCode, String fieldCode, FieldOptionRequest optionRequest) {
        validateModelCode(modelCode);
        logOperation("getFieldOptions", modelCode, fieldCode);

        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition fieldDef = findFieldDefinition(model, fieldCode);

        // Get refTarget config from field extraProps
        Map<String, Object> extraProps = fieldDef.getExtraProps();
        if (extraProps == null || !extraProps.containsKey("refTarget")) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> refTarget = (Map<String, Object>) extraProps.get("refTarget");
        String targetTable = (String) refTarget.get("table");
        String valueField = (String) refTarget.getOrDefault("valueField", "id");
        String displayField = (String) refTarget.getOrDefault("displayField", "name");

        if (targetTable == null) {
            return Collections.emptyList();
        }

        // Security: validate SQL identifiers to prevent injection
        java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
        if (!NAME_PATTERN.matcher(targetTable).matches()
                || !NAME_PATTERN.matcher(valueField).matches()
                || !NAME_PATTERN.matcher(displayField).matches()) {
            log.warn("Invalid SQL identifier in refTarget config: table={}, value={}, display={}", targetTable, valueField, displayField);
            return Collections.emptyList();
        }

        Long tenantId = getCurrentTenantId();
        int limit = optionRequest != null && optionRequest.getLimit() != null ? optionRequest.getLimit() : 50;
        int offset = optionRequest != null && optionRequest.getOffset() != null ? optionRequest.getOffset() : 0;

        // Build query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(valueField).append(", ").append(displayField);
        sql.append(" FROM ").append(targetTable);
        sql.append(" WHERE tenant_id = #{params.tenantId}");

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        // Row-level permission filter on reference target model (fail-secure)
        try {
            String refModelCode = (String) refTarget.get("targetModel");
            if (refModelCode != null) {
                Long userId = getCurrentUserId();
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, refModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    sql.append(" ").append(rowFilter);
                }
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level permission in getFieldOptions — denying access", e);
            throw new MetaServiceException("Data permission evaluation failed for field options");
        }

        // Add keyword filter
        if (optionRequest != null && optionRequest.getKeyword() != null && !optionRequest.getKeyword().isBlank()) {
            sql.append(" AND ").append(displayField).append(" ILIKE #{params.keyword}");
            params.put("keyword", "%" + optionRequest.getKeyword() + "%");
        }

        // Add group filter
        if (optionRequest != null && optionRequest.getGroup() != null && !optionRequest.getGroup().isBlank()) {
            String groupField = (String) refTarget.getOrDefault("groupField", "group_code");
            if (!NAME_PATTERN.matcher(groupField).matches()) {
                log.warn("Invalid SQL identifier for groupField: {}", groupField);
                return Collections.emptyList();
            }
            sql.append(" AND ").append(groupField).append(" = #{params.groupValue}");
            params.put("groupValue", optionRequest.getGroup());
        }

        sql.append(" ORDER BY ").append(displayField);
        sql.append(" LIMIT ").append(limit);
        sql.append(" OFFSET ").append(offset);

        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql.toString(), params);

        // Convert to FieldOption list
        List<FieldOption> options = new ArrayList<>();
        int sortOrder = offset;
        for (Map<String, Object> row : results) {
            options.add(FieldOption.builder()
                    .value(row.get(valueField) != null ? row.get(valueField).toString() : null)
                    .label(row.get(displayField) != null ? row.get(displayField).toString() : null)
                    .sortOrder(sortOrder++)
                    .build());
        }

        return options;
    }

    // ==================== Export ====================

    @Override
    @Transactional(readOnly = true)
    public ExportResult exportData(String modelCode, DataExportRequest exportRequest) {
        validateModelCode(modelCode);
        logOperation("exportData", modelCode, exportRequest);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        // Permission checks BEFORE outer try — failures must NOT be swallowed
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        String rowFilter;
        try {
            rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission in export for model {} — denying access", modelCode, e);
            throw new MetaServiceException("Data permission evaluation failed for export: " + modelCode);
        }

        String domainFilter;
        try {
            domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
        } catch (Exception e) {
            log.error("Failed to apply domain filter in export for model {} — denying access", modelCode, e);
            throw new MetaServiceException("Data domain filter failed for export: " + modelCode);
        }

        try {
            // Build query for export data
            List<QueryCondition> conditions = exportRequest.getConditions() != null
                    ? exportRequest.getConditions() : Collections.emptyList();
            QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(model, conditions);
            queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

            // Apply row-level permission filter
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }

            // Apply domain isolation filter
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }

            // Add sort
            if (exportRequest.getSortFields() != null && !exportRequest.getSortFields().isEmpty()) {
                List<SortField> mappedSortFields = mapSortFields(model, exportRequest.getSortFields());
                queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, mappedSortFields, model);
            }

            // Add limit
            if (exportRequest.getLimit() != null && exportRequest.getLimit() > 0) {
                queryBuilder.setLimit(exportRequest.getLimit());
            }

            String sql = queryBuilder.getSql();
            Map<String, Object> paramMap = queryBuilder.getParameterMap();
            List<Map<String, Object>> data = dynamicDataMapper.selectByQuery(sql, paramMap);

            // Apply policy-based field masking (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    data = dataPermissionEngine.applyFieldMasking(data, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply policy-based masking in export for model {} — denying access", modelCode, e);
                throw new MetaServiceException("Policy-based masking failed for export: " + modelCode);
            }

            // Apply configurable field masking for export (A9)
            try {
                data = fieldMaskService.applyMaskingForExport(modelCode, data, userId);
            } catch (Exception e) {
                log.warn("Failed to apply export masking for model {}: {}", modelCode, e.getMessage());
            }

            // Determine export fields
            List<String> exportFields = exportRequest.getFields();
            if (exportFields == null || exportFields.isEmpty()) {
                exportFields = model.getFields().stream()
                        .map(FieldDefinition::getCode)
                        .collect(Collectors.toList());
            }

            // Build field code → display label map for human-readable headers
            Map<String, String> fieldLabelMap = buildFieldLabelMap(model.getFields());

            // Generate export file
            DataExportRequest.ExportFormat format = exportRequest.getFormat() != null
                    ? exportRequest.getFormat() : DataExportRequest.ExportFormat.CSV;
            String fileName = exportRequest.getFileName() != null
                    ? exportRequest.getFileName()
                    : modelCode + "_export_" + System.currentTimeMillis();

            Path tempFile;
            switch (format) {
                case EXCEL:
                    tempFile = exportAsExcel(data, exportFields, fieldLabelMap, fileName, exportRequest.getIncludeHeader());
                    break;
                case JSON:
                    tempFile = exportAsJson(data, exportFields, fieldLabelMap, fileName);
                    break;
                case CSV:
                default:
                    tempFile = exportAsCsv(data, exportFields, fieldLabelMap, fileName, exportRequest.getIncludeHeader());
                    break;
            }

            long fileSize = Files.size(tempFile);
            return ExportResult.builder()
                    .success(true)
                    .filePath(tempFile.toString())
                    .recordCount((long) data.size())
                    .fileSize(fileSize)
                    .format(format.name())
                    .exportTime(startTime)
                    .build();

        } catch (MetaServiceException e) {
            throw e; // Never swallow permission failures
        } catch (Exception e) {
            log.error("Export failed for model {}: {}", modelCode, e.getMessage(), e);
            return ExportResult.builder()
                    .success(false)
                    .errorMessage("Export failed: " + e.getMessage())
                    .format(exportRequest.getFormat() != null ? exportRequest.getFormat().name() : "csv")
                    .build();
        }
    }

    // ==================== Import ====================

    @Override
    @Transactional
    public ImportResult importData(String modelCode, DataImportRequest importRequest) {
        validateModelCode(modelCode);
        logOperation("importData", modelCode, importRequest);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        try {
            // Validate file exists
            Path filePath = Paths.get(importRequest.getFilePath());
            if (!Files.exists(filePath)) {
                return ImportResult.builder()
                        .success(false)
                        .summary("Import file not found: " + importRequest.getFilePath())
                        .build();
            }

            // Parse file to data list
            List<Map<String, Object>> records;
            DataImportRequest.ImportFormat format = importRequest.getFormat() != null
                    ? importRequest.getFormat() : DataImportRequest.ImportFormat.CSV;

            switch (format) {
                case JSON:
                    records = parseJsonImport(filePath);
                    break;
                case CSV:
                default:
                    records = parseCsvImport(filePath, importRequest.getSkipFirstRow());
                    break;
            }

            // Apply field mapping
            Map<String, String> fieldMapping = importRequest.getFieldMapping();
            if (fieldMapping != null && !fieldMapping.isEmpty()) {
                records = records.stream()
                        .map(row -> applyFieldMapping(row, fieldMapping))
                        .collect(Collectors.toList());
            }

            // Batch insert
            int batchSize = importRequest.getBatchSize() != null ? importRequest.getBatchSize() : 100;
            int successCount = 0;
            int failedCount = 0;
            List<ImportResult.ImportError> errors = new ArrayList<>();
            Long tenantId = getCurrentTenantId();

            for (int i = 0; i < records.size(); i += batchSize) {
                int end = Math.min(i + batchSize, records.size());
                List<Map<String, Object>> batch = records.subList(i, end);

                for (int j = 0; j < batch.size(); j++) {
                    int rowIndex = i + j;
                    try {
                        Map<String, Object> record = batch.get(j);
                        // Add system columns
                        Map<String, Object> columnData = toColumnData(model, record);
                        columnData.put("tenant_id", tenantId);
                        columnData.put("created_at", Instant.now());
                        columnData.put("created_by", getCurrentUserId());

                        Set<String> batchJsonbCols = JsonbFieldHelper.getJsonbHostColumns(model);
                        if (batchJsonbCols.isEmpty()) {
                            dynamicDataMapper.insert(model.getTableName(), columnData);
                        } else {
                            dynamicDataMapper.insertWithJsonb(model.getTableName(), columnData, batchJsonbCols);
                        }
                        successCount++;
                    } catch (Exception e) {
                        failedCount++;
                        errors.add(ImportResult.ImportError.builder()
                                .rowNumber(rowIndex + 1)
                                .fieldName(null)
                                .errorMessage(e.getMessage())
                                .build());
                    }
                }
            }

            return ImportResult.builder()
                    .success(failedCount == 0)
                    .totalCount(records.size())
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .errors(errors)
                    .importTime(startTime)
                    .summary(String.format("Imported %d/%d records", successCount, records.size()))
                    .build();

        } catch (Exception e) {
            log.error("Import failed for model {}: {}", modelCode, e.getMessage(), e);
            return ImportResult.builder()
                    .success(false)
                    .summary("Import failed: " + e.getMessage())
                    .build();
        }
    }

    // ==================== Custom Action ====================

    @Override
    @Transactional
    public ActionExecutionResult executeCustomAction(String modelCode, String actionName, Map<String, Object> actionParams) {
        validateModelCode(modelCode);
        logOperation("executeCustomAction", modelCode, actionName);

        Instant startTime = Instant.now();
        ModelDefinition model = getModelDefinition(modelCode);

        try {
            Map<String, Object> resultData = new HashMap<>();

            switch (actionName) {
                case "count": {
                    String sql = "SELECT COUNT(*) as cnt FROM " + model.getTableName()
                            + " WHERE tenant_id = #{params.tenantId}";
                    Map<String, Object> params = Map.of("tenantId", getCurrentTenantId());
                    List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, params);
                    long count = results.isEmpty() ? 0 : ((Number) results.get(0).get("cnt")).longValue();
                    resultData.put("count", count);
                    break;
                }
                case "truncate": {
                    Map<String, Object> conditions = new HashMap<>();
                    conditions.put("tenant_id", getCurrentTenantId());
                    int deleted = dynamicDataMapper.delete(model.getTableName(), conditions);
                    resultData.put("deletedCount", deleted);
                    break;
                }
                default:
                    return ActionExecutionResult.builder()
                            .success(false)
                            .actionName(actionName)
                            .errorMessage("Unsupported action: " + actionName)
                            .executionTime(startTime)
                            .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                            .build();
            }

            return ActionExecutionResult.builder()
                    .success(true)
                    .actionName(actionName)
                    .resultData(resultData)
                    .message("Action '" + actionName + "' executed successfully")
                    .executionTime(startTime)
                    .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                    .build();

        } catch (Exception e) {
            log.error("Custom action '{}' failed for model {}: {}", actionName, modelCode, e.getMessage(), e);
            return ActionExecutionResult.builder()
                    .success(false)
                    .actionName(actionName)
                    .errorMessage("Action failed: " + e.getMessage())
                    .executionTime(startTime)
                    .duration(java.time.Duration.between(startTime, Instant.now()).toMillis())
                    .build();
        }
    }

    // 私有辅助方法
    private ModelDefinition getModelDefinition(String modelCode) {
        return metadataService.getModelDefinition(modelCode)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
    }

    private Map<String, Object> toColumnData(ModelDefinition model, Map<String, Object> data) {
        // Step 1: Merge JSONB virtual fields into host columns
        Map<String, Object> mergedData = JsonbFieldHelper.mergeJsonbFields(model, data);

        // Step 2: Map field codes to column names (only for non-JSONB-virtual fields)
        Map<String, Object> columnData = new HashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        for (FieldDefinition field : model.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : mergedData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_COLUMNS.contains(key)) {
                columnData.put(key, entry.getValue());
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                Object value = entry.getValue();
                // Serialize Map values for JSONB host columns
                if (hostColumns.contains(columnName) && value instanceof Map) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
                continue;
            }
            // Could be a JSONB host column from mergeJsonbFields (key is already a column name)
            if (hostColumns.contains(key)) {
                Object value = entry.getValue();
                columnData.put(key, value instanceof Map ? JsonbFieldHelper.toJsonString(value) : value);
                continue;
            }
            throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + key);
        }

        return columnData;
    }

    /**
     * toColumnData variant for UPDATE that preserves unmodified JSONB keys.
     */
    private Map<String, Object> toColumnDataForUpdate(ModelDefinition model, Map<String, Object> data, Map<String, Object> existingRecord) {
        // Step 1: Merge JSONB virtual fields, preserving unmodified keys from existing record
        Map<String, Object> mergedData = JsonbFieldHelper.mergeJsonbFieldsForUpdate(model, data, existingRecord);

        // Step 2: Same column mapping as toColumnData
        Map<String, Object> columnData = new HashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        for (FieldDefinition field : model.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : mergedData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_COLUMNS.contains(key)) {
                columnData.put(key, entry.getValue());
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                Object value = entry.getValue();
                if (hostColumns.contains(columnName) && value instanceof Map) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
                continue;
            }
            if (hostColumns.contains(key)) {
                Object value = entry.getValue();
                columnData.put(key, value instanceof Map ? JsonbFieldHelper.toJsonString(value) : value);
                continue;
            }
            throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + key);
        }

        return columnData;
    }

    private RelationDefinition findRelation(ModelDefinition model, String relationName) {
        if (model.getRelations() == null) {
            throw new MetaServiceException("Model " + model.getCode() + " has no relations defined");
        }
        return model.getRelations().stream()
                .filter(r -> relationName.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "Relation '" + relationName + "' not found in model " + model.getCode()));
    }

    private FieldDefinition findFieldDefinition(ModelDefinition model, String fieldCode) {
        return model.getFields().stream()
                .filter(f -> fieldCode.equals(f.getCode()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "Field '" + fieldCode + "' not found in model " + model.getCode()));
    }

    private String resolveColumnName(ModelDefinition model, String fieldName) {
        if (SYSTEM_COLUMNS.contains(fieldName) || "*".equals(fieldName)) {
            return fieldName;
        }
        for (FieldDefinition field : model.getFields()) {
            if (fieldName.equals(field.getCode()) || fieldName.equals(field.getColumnName())) {
                // JSONB virtual fields use their typed expression for WHERE/ORDER BY
                if (field.isJsonbVirtual()) {
                    return field.getJsonbFilterExpression();
                }
                return field.getColumnName();
            }
        }
        throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + fieldName);
    }

    private Path exportAsExcel(List<Map<String, Object>> data, List<String> fields,
                               Map<String, String> fieldLabelMap, String fileName, Boolean includeHeader)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("Data");

            // Create default font with Chinese support
            org.apache.poi.xssf.usermodel.XSSFFont defaultFont = workbook.createFont();
            defaultFont.setFontName("Arial Unicode MS");
            defaultFont.setFontHeightInPoints((short) 11);

            // Create default cell style
            org.apache.poi.xssf.usermodel.XSSFCellStyle defaultStyle = workbook.createCellStyle();
            defaultStyle.setFont(defaultFont);

            int rowNum = 0;

            // Write header
            if (!Boolean.FALSE.equals(includeHeader)) {
                org.apache.poi.xssf.usermodel.XSSFRow headerRow = sheet.createRow(rowNum++);
                // Create header style
                org.apache.poi.xssf.usermodel.XSSFCellStyle headerStyle = workbook.createCellStyle();
                org.apache.poi.xssf.usermodel.XSSFFont headerFont = workbook.createFont();
                headerFont.setFontName("Arial Unicode MS");
                headerFont.setFontHeightInPoints((short) 11);
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
                headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

                for (int i = 0; i < fields.size(); i++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = headerRow.createCell(i);
                    cell.setCellValue(fieldLabelMap != null
                            ? fieldLabelMap.getOrDefault(fields.get(i), fields.get(i))
                            : fields.get(i));
                    cell.setCellStyle(headerStyle);
                }
            }

            // Write data rows
            for (Map<String, Object> row : data) {
                org.apache.poi.xssf.usermodel.XSSFRow dataRow = sheet.createRow(rowNum++);
                for (int i = 0; i < fields.size(); i++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = dataRow.createCell(i);
                    cell.setCellStyle(defaultStyle);
                    Object val = row.get(fields.get(i));
                    if (val != null) {
                        if (val instanceof Number) {
                            cell.setCellValue(((Number) val).doubleValue());
                        } else if (val instanceof Boolean) {
                            cell.setCellValue((Boolean) val);
                        } else if (val instanceof java.util.Date) {
                            cell.setCellValue((java.util.Date) val);
                        } else if (val instanceof java.time.LocalDateTime) {
                            cell.setCellValue(val.toString());
                        } else if (val instanceof java.time.Instant) {
                            cell.setCellValue(val.toString());
                        } else {
                            cell.setCellValue(val.toString());
                        }
                    }
                }
            }

            // Auto-size columns (with minimum width for Chinese characters)
            for (int i = 0; i < fields.size(); i++) {
                sheet.autoSizeColumn(i);
                // Ensure minimum width for Chinese content
                int currentWidth = sheet.getColumnWidth(i);
                if (currentWidth < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            // Write to file
            try (java.io.OutputStream os = Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
        }
        return tempFile;
    }

    private Path exportAsCsv(List<Map<String, Object>> data, List<String> fields,
                             Map<String, String> fieldLabelMap, String fileName, Boolean includeHeader)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            // Write header with display labels
            if (!Boolean.FALSE.equals(includeHeader)) {
                List<String> headerLabels = fields.stream()
                        .map(f -> fieldLabelMap != null
                                ? fieldLabelMap.getOrDefault(f, f) : f)
                        .collect(Collectors.toList());
                writer.write(String.join(",", headerLabels));
                writer.newLine();
            }
            // Write data
            for (Map<String, Object> row : data) {
                List<String> values = fields.stream()
                        .map(field -> {
                            Object val = row.get(field);
                            if (val == null) return "";
                            String str = val.toString();
                            if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
                                return "\"" + str.replace("\"", "\"\"") + "\"";
                            }
                            return str;
                        })
                        .collect(Collectors.toList());
                writer.write(String.join(",", values));
                writer.newLine();
            }
        }
        return tempFile;
    }

    private Path exportAsJson(List<Map<String, Object>> data, List<String> fields,
                              Map<String, String> fieldLabelMap, String fileName)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".json");
        // Filter to only include specified fields, using display labels as keys
        List<Map<String, Object>> filtered = data.stream()
                .map(row -> {
                    Map<String, Object> filteredRow = new LinkedHashMap<>();
                    for (String field : fields) {
                        String key = (fieldLabelMap != null)
                                ? fieldLabelMap.getOrDefault(field, field) : field;
                        filteredRow.put(key, row.get(field));
                    }
                    return filteredRow;
                })
                .collect(Collectors.toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), filtered);
        return tempFile;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonImport(Path filePath) throws IOException {
        Object parsed = objectMapper.readValue(filePath.toFile(), Object.class);
        if (parsed instanceof List) {
            return (List<Map<String, Object>>) parsed;
        }
        throw new MetaServiceException("JSON import file must contain an array of objects");
    }

    private List<Map<String, Object>> parseCsvImport(Path filePath, Boolean skipFirstRow) throws IOException {
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        // First line is header
        String[] headers = lines.get(0).split(",", -1);
        for (int i = 0; i < headers.length; i++) {
            headers[i] = headers[i].trim().replace("\"", "");
        }

        int startLine = Boolean.FALSE.equals(skipFirstRow) ? 0 : 1;
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] values = parseCsvLine(line);
            Map<String, Object> record = new LinkedHashMap<>();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                String val = values[j].trim();
                record.put(headers[j], val.isEmpty() ? null : val);
            }
            records.add(record);
        }
        return records;
    }

    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private Map<String, Object> applyFieldMapping(Map<String, Object> row, Map<String, String> fieldMapping) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String targetField = fieldMapping.getOrDefault(entry.getKey(), entry.getKey());
            mapped.put(targetField, entry.getValue());
        }
        return mapped;
    }

    private List<SortField> mapSortFields(ModelDefinition model, List<SortField> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> codeToColumn = new HashMap<>();
        for (FieldDefinition field : model.getFields()) {
            codeToColumn.put(field.getCode(), field.getColumnName());
            codeToColumn.put(field.getColumnName(), field.getColumnName());
        }

        List<SortField> mappedFields = new ArrayList<>();
        for (SortField sortField : sortFields) {
            String columnName = codeToColumn.get(sortField.getFieldName());
            if (columnName == null && !SYSTEM_COLUMNS.contains(sortField.getFieldName())) {
                throw new MetaServiceException("Unknown sort field for model " + model.getCode() + ": " + sortField.getFieldName());
            }
            mappedFields.add(SortField.builder()
                    .fieldName(SYSTEM_COLUMNS.contains(sortField.getFieldName()) ? sortField.getFieldName() : columnName)
                    .direction(sortField.getDirection())
                    .priority(sortField.getPriority())
                    .build());
        }
        return mappedFields;
    }

    // ==================== Joint Sub-Table Save ====================

    @Override
    @Transactional
    public JointSubTableSaveResponse saveWithRelations(String modelCode, JointSubTableSaveRequest request) {
        validateModelCode(modelCode);
        if (request == null || request.getMasterData() == null) {
            throw new MetaServiceException("Request and master data cannot be null");
        }

        long startTime = System.currentTimeMillis();
        logOperation("saveWithRelations", modelCode, request.getMasterData().keySet());

        ModelDefinition masterModel = getModelDefinition(modelCode);
        FieldDefinition primaryKey = metadataService.getPrimaryKeyField(modelCode);
        String pkField = primaryKey.getCode();

        List<String> errors = new ArrayList<>();
        Map<String, Integer> subTableCounts = new HashMap<>();
        Map<String, List<Map<String, Object>>> savedRecords = new HashMap<>();
        Map<String, List<JointSubTableSaveResponse.SubTableError>> subTableErrors = new HashMap<>();

        try {
            // Step 1: Determine if this is create or update
            Object existingPkValue = request.getMasterData().get(pkField);
            boolean isUpdate = existingPkValue != null && !existingPkValue.toString().trim().isEmpty();
            JointSubTableSaveResponse.OperationType opType;
            Map<String, Object> savedMaster;
            String masterId;

            // Step 2: Save master record
            if (isUpdate) {
                opType = JointSubTableSaveResponse.OperationType.UPDATE;
                masterId = existingPkValue.toString();
                savedMaster = update(modelCode, masterId, request.getMasterData());
                log.info("Updated master record: model={}, id={}", modelCode, masterId);
            } else {
                opType = JointSubTableSaveResponse.OperationType.CREATE;
                savedMaster = create(modelCode, request.getMasterData());
                masterId = savedMaster.get(pkField).toString();
                log.info("Created master record: model={}, id={}", modelCode, masterId);
            }

            // Step 3: Process each sub-table
            if (request.getTables() != null && !request.getTables().isEmpty()) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : request.getTables().entrySet()) {
                    String tableKey = entry.getKey();
                    List<Map<String, Object>> childRows = entry.getValue();

                    if (childRows == null) {
                        continue;
                    }

                    // Resolve relation name
                    String relationName = tableKey;
                    if (request.getRelationMappings() != null && request.getRelationMappings().containsKey(tableKey)) {
                        relationName = request.getRelationMappings().get(tableKey);
                    }

                    try {
                        // Find relation definition
                        RelationDefinition relation = findRelationByName(masterModel, relationName);
                        if (relation == null) {
                            errors.add("Relation '" + relationName + "' not found in model " + modelCode);
                            continue;
                        }

                        // Get target model
                        String targetModelCode = relation.getTargetModel();
                        ModelDefinition targetModel = getModelDefinition(targetModelCode);

                        // Delete existing child records if replace mode
                        if (Boolean.TRUE.equals(request.getReplaceExisting()) && isUpdate) {
                            deleteExistingChildRecords(relation, masterId);
                        }

                        // Save child records
                        List<Map<String, Object>> savedChildren = new ArrayList<>();
                        List<JointSubTableSaveResponse.SubTableError> rowErrors = new ArrayList<>();
                        int successCount = 0;

                        for (int i = 0; i < childRows.size(); i++) {
                            Map<String, Object> childData = new HashMap<>(childRows.get(i));

                            try {
                                // Inject foreign key
                                String fkField = relation.getTargetField();
                                childData.put(fkField, masterId);

                                // Create child record
                                Map<String, Object> savedChild = create(targetModelCode, childData);
                                savedChildren.add(savedChild);
                                successCount++;
                            } catch (Exception e) {
                                log.warn("Failed to save child record at index {} for relation {}: {}",
                                        i, relationName, e.getMessage());
                                rowErrors.add(JointSubTableSaveResponse.SubTableError.builder()
                                        .rowIndex(i)
                                        .message(e.getMessage())
                                        .data(childData)
                                        .build());
                            }
                        }

                        subTableCounts.put(relationName, successCount);
                        savedRecords.put(relationName, savedChildren);

                        if (!rowErrors.isEmpty()) {
                            subTableErrors.put(relationName, rowErrors);
                            errors.add("Sub-table '" + relationName + "' had " + rowErrors.size() + " errors");
                        }

                        log.info("Saved {} records for relation: {}", successCount, relationName);

                    } catch (Exception e) {
                        log.error("Failed to process sub-table {}: {}", tableKey, e.getMessage(), e);
                        errors.add("Sub-table '" + tableKey + "': " + e.getMessage());
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            return JointSubTableSaveResponse.builder()
                    .success(errors.isEmpty())
                    .masterId(masterId)
                    .masterRecord(savedMaster)
                    .subTableCounts(subTableCounts)
                    .savedRecords(savedRecords)
                    .subTableErrors(subTableErrors)
                    .errors(errors)
                    .duration(duration)
                    .operationType(opType)
                    .build();

        } catch (Exception e) {
            log.error("Joint save failed for model {}: {}", modelCode, e.getMessage(), e);
            long duration = System.currentTimeMillis() - startTime;
            errors.add("Master save failed: " + e.getMessage());
            return JointSubTableSaveResponse.failure(errors, duration);
        }
    }

    /**
     * Find relation by name (supports both relation name and target model code)
     */
    private RelationDefinition findRelationByName(ModelDefinition model, String relationName) {
        if (model.getRelations() == null || model.getRelations().isEmpty()) {
            return null;
        }

        // First try exact name match
        for (RelationDefinition relation : model.getRelations()) {
            if (relationName.equals(relation.getName())) {
                return relation;
            }
        }

        // Try target model code match
        for (RelationDefinition relation : model.getRelations()) {
            if (relationName.equals(relation.getTargetModel())) {
                return relation;
            }
        }

        return null;
    }

    /**
     * Delete existing child records for a relation
     */
    private void deleteExistingChildRecords(RelationDefinition relation, String masterId) {
        Long tenantId = getCurrentTenantId();

        if (relation.getRelationType() == RelationDefinition.RelationType.MANY_TO_MANY) {
            // For M2M, delete from join table
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(relation.getSourceField(), masterId);
            conditions.put("tenant_id", tenantId);
            dynamicDataMapper.delete(relation.getJoinTable(), conditions);
            log.debug("Deleted existing M2M relations from {} for master {}", relation.getJoinTable(), masterId);
        } else if (relation.getRelationType() == RelationDefinition.RelationType.ONE_TO_MANY) {
            // For O2M, delete from target table
            Map<String, Object> conditions = new HashMap<>();
            conditions.put(relation.getTargetField(), masterId);
            conditions.put("tenant_id", tenantId);
            dynamicDataMapper.delete(relation.getTargetTable(), conditions);
            log.debug("Deleted existing child records from {} for master {}", relation.getTargetTable(), masterId);
        }
    }
}
