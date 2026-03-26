package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import com.auraboot.framework.meta.entity.NamedQueryStatus;
import com.auraboot.framework.meta.entity.NamedQueryVersion;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.NamedQueryVersionMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.*;
import java.util.stream.Collectors;

import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Named Query Service Implementation
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamedQueryServiceImpl extends BaseMetaService implements NamedQueryService {

    private final NamedQueryMapper namedQueryMapper;
    private final NamedQueryFieldMapper namedQueryFieldMapper;
    private final NamedQueryVersionMapper namedQueryVersionMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final NamedQueryRateLimiter rateLimiter;
    private final ApiConnectorService apiConnectorService;

    // DANGEROUS_SQL_PATTERN removed — replaced by SqlSafetyUtils.validateSelectOnlySql()

    private static final Set<String> ALLOWED_OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "like", "ilike",
            "in", "not_in", "is_null", "is_not_null", "between",
            "starts_with", "ends_with", "contains"
    );

    // ==================== CRUD ====================

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries"}, allEntries = true)
    public NamedQueryDTO create(NamedQueryCreateRequest request) {
        Long tenantId = getCurrentTenantId();

        // Check code uniqueness
        int count = namedQueryMapper.countByCode(request.getCode(), null);
        if (count > 0) {
            throw new MetaServiceException("Named query code already exists: " + request.getCode());
        }

        // Validate: either fromSql or connectorPid must be set
        boolean isConnector = request.getConnectorPid() != null && !request.getConnectorPid().isBlank();
        if (!isConnector) {
            validateFromSql(request.getFromSql());
        } else {
            if (request.getConnectorEndpointCode() == null || request.getConnectorEndpointCode().isBlank()) {
                throw new MetaServiceException("connectorEndpointCode is required when connectorPid is set");
            }
        }

        // Build entity
        NamedQuery entity = new NamedQuery(tenantId, request.getCode(), request.getTitle(),
                isConnector ? null : request.getFromSql());
        entity.setPid(UlidGenerator.generate());
        entity.setDescription(request.getDescription());
        entity.setBaseWhere(request.getBaseWhere());
        entity.setDefaultOrder(request.getDefaultOrder());
        entity.setStatus(request.getStatus() != null
                ? NamedQueryStatus.fromString(request.getStatus()).name().toLowerCase(Locale.ROOT)
                : StatusConstants.DRAFT);
        if (isConnector) {
            entity.setConnectorPid(request.getConnectorPid());
            entity.setConnectorEndpointCode(request.getConnectorEndpointCode());
        }

        namedQueryMapper.insert(entity);

        // Create fields if provided
        if (request.getFields() != null && !request.getFields().isEmpty()) {
            for (NamedQueryFieldRequest fieldReq : request.getFields()) {
                createFieldEntity(tenantId, request.getCode(), fieldReq);
            }
        }

        log.info("Created named query: code={}, pid={}", entity.getCode(), entity.getPid());
        return toDTO(entity, true);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries"}, allEntries = true)
    public NamedQueryDTO update(String pid, NamedQueryUpdateRequest request) {
        NamedQuery entity = namedQueryMapper.findByPid(pid);
        if (entity == null) {
            throw new MetaServiceException("Named query not found: " + pid);
        }

        // Always allow title/description updates
        if (request.getTitle() != null) {
            entity.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }

        // Enforce frozen state: from_sql, baseWhere, defaultOrder are read-only when frozen
        boolean frozen = entity.isFrozen();
        if (request.getFromSql() != null) {
            if (frozen) {
                throw new MetaServiceException("Cannot modify FROM SQL: query is " + entity.getStatus() + " (frozen)");
            }
            validateFromSql(request.getFromSql());
            entity.setFromSql(request.getFromSql());
        }
        if (request.getBaseWhere() != null) {
            if (frozen) {
                throw new MetaServiceException("Cannot modify base WHERE: query is " + entity.getStatus() + " (frozen)");
            }
            entity.setBaseWhere(request.getBaseWhere());
        }
        if (request.getDefaultOrder() != null) {
            if (frozen) {
                throw new MetaServiceException("Cannot modify default order: query is " + entity.getStatus() + " (frozen)");
            }
            entity.setDefaultOrder(request.getDefaultOrder());
        }
        // Status changes go through updateStatus() — ignore here

        // Policy can always be updated (not frozen)
        if (request.getPolicy() != null) {
            entity.setPolicy(request.getPolicy());
        }

        entity.setUpdatedAt(Instant.now());
        namedQueryMapper.updateById(entity);

        log.info("Updated named query: pid={}", pid);
        return toDTO(entity, false);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries"}, allEntries = true)
    public void delete(String pid) {
        NamedQuery entity = namedQueryMapper.findByPid(pid);
        if (entity == null) {
            throw new MetaServiceException("Named query not found: " + pid);
        }

        Long tenantId = getCurrentTenantId();
        // Delete associated fields
        namedQueryFieldMapper.deleteByQuery(tenantId, entity.getCode());
        // Delete query
        namedQueryMapper.deleteById(entity.getId());

        log.info("Deleted named query: pid={}, code={}", pid, entity.getCode());
    }

    @Override
    @Cacheable(value = "namedQuery", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':pid:' + #pid")
    public NamedQueryDTO findByPid(String pid) {
        NamedQuery entity = namedQueryMapper.findByPid(pid);
        if (entity == null) {
            throw new MetaServiceException("Named query not found: " + pid);
        }
        return toDTO(entity, true);
    }

    @Override
    @Cacheable(value = "namedQuery", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':code:' + #code")
    public NamedQueryDTO findByCode(String code) {
        NamedQuery entity = namedQueryMapper.findByCode(code);
        if (entity == null) {
            throw new MetaServiceException("Named query not found: " + code);
        }
        return toDTO(entity, true);
    }

    // ==================== List queries ====================

    @Override
    public PaginationResult<NamedQueryDTO> list(NamedQueryQueryRequest request) {
        Page<NamedQuery> page = new Page<>(request.getPage(), request.getSize());
        QueryWrapper<NamedQuery> wrapper = new QueryWrapper<>();

        // Apply filters
        if (request.getCode() != null && !request.getCode().isEmpty()) {
            wrapper.like("code", request.getCode());
        }
        if (request.getTitle() != null && !request.getTitle().isEmpty()) {
            wrapper.like("title", request.getTitle());
        }
        if (request.getStatus() != null && !request.getStatus().isEmpty()) {
            wrapper.eq("status", request.getStatus());
        }
        if (Boolean.TRUE.equals(request.getEnabledOnly())) {
            wrapper.eq("status", StatusConstants.ENABLED);
        }
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            wrapper.and(w -> w.like("code", request.getKeyword())
                    .or().like("title", request.getKeyword())
                    .or().like("description", request.getKeyword()));
        }
        if (request.getCreatedAtStart() != null) {
            wrapper.ge("created_at", request.getCreatedAtStart().toInstant(ZoneOffset.UTC));
        }
        if (request.getCreatedAtEnd() != null) {
            wrapper.le("created_at", request.getCreatedAtEnd().toInstant(ZoneOffset.UTC));
        }

        // Apply sort
        String sortBy = request.getSortBy() != null ? request.getSortBy() : "created_at";
        String sortColumn = mapSortColumn(sortBy);
        if ("asc".equalsIgnoreCase(request.getSortDirection())) {
            wrapper.orderByAsc(sortColumn);
        } else {
            wrapper.orderByDesc(sortColumn);
        }

        Page<NamedQuery> result = namedQueryMapper.selectPage(page, wrapper);

        List<NamedQueryDTO> dtos = result.getRecords().stream()
                .map(e -> toDTO(e, Boolean.TRUE.equals(request.getIncludeFields())))
                .collect(Collectors.toList());

        return PaginationResult.of(dtos, result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    @Cacheable(value = "namedQueries", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':enabled'")
    public List<NamedQueryDTO> findEnabled() {
        List<NamedQuery> entities = namedQueryMapper.findEnabledByTenant();
        return entities.stream()
                .map(e -> toDTO(e, false))
                .collect(Collectors.toList());
    }

    // ==================== Status management ====================

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries"}, allEntries = true)
    public NamedQueryDTO updateStatus(String pid, String status) {
        NamedQuery entity = namedQueryMapper.findByPid(pid);
        if (entity == null) {
            throw new MetaServiceException("Named query not found: " + pid);
        }

        NamedQueryStatus currentStatus = entity.getStatusEnum();
        NamedQueryStatus targetStatus = NamedQueryStatus.fromString(status);

        // Validate state transition
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new MetaServiceException(
                    "Invalid status transition: " + currentStatus + " → " + targetStatus
                            + ". Allowed transitions from " + currentStatus + ": "
                            + java.util.Arrays.toString(
                            java.util.Arrays.stream(NamedQueryStatus.values())
                                    .filter(currentStatus::canTransitionTo)
                                    .toArray()));
        }

        // Record publish/deprecate timestamps
        Instant now = Instant.now();
        if (targetStatus == NamedQueryStatus.PUBLISHED) {
            if (entity.getPublishedAt() == null) {
                entity.setPublishedAt(now);
            }
            entity.setPublishedBy(getCurrentUserId());

            // Create version snapshot
            createVersionSnapshot(entity, now);
        }
        if (targetStatus == NamedQueryStatus.DEPRECATED) {
            entity.setDeprecatedAt(now);
        }

        entity.setStatus(targetStatus.name().toLowerCase(Locale.ROOT));
        entity.setUpdatedAt(now);
        namedQueryMapper.updateById(entity);

        log.info("Updated named query status: pid={}, {} → {}", pid, currentStatus, targetStatus);
        return toDTO(entity, false);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries"}, allEntries = true)
    public NamedQueryBatchResult batchUpdateStatus(NamedQueryBatchStatusRequest request) {
        NamedQueryBatchResult result = new NamedQueryBatchResult("status_update", request.getPids().size());

        for (String pid : request.getPids()) {
            try {
                int updated = namedQueryMapper.updateStatusByPid(pid, request.getTargetStatus());
                if (updated > 0) {
                    result.addSuccess(pid);
                } else {
                    result.addFailure(pid, "Named query not found: " + pid);
                }
            } catch (Exception e) {
                result.addFailure(pid, e.getMessage());
            }
        }

        result.complete();
        return result;
    }

    // ==================== Field management ====================

    @Override
    public List<NamedQueryFieldDTO> getFields(String queryCode) {
        Long tenantId = getCurrentTenantId();
        List<NamedQueryField> fields = namedQueryFieldMapper.findByQueryCode(tenantId, queryCode);
        return fields.stream()
                .map(this::toFieldDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public NamedQueryFieldDTO addField(String queryCode, NamedQueryFieldRequest request) {
        Long tenantId = getCurrentTenantId();

        // Enforce frozen state
        NamedQuery query = namedQueryMapper.findByCode(queryCode);
        if (query != null && query.isFrozen()) {
            throw new MetaServiceException("Cannot add fields: query is " + query.getStatus() + " (frozen)");
        }

        // Check field code uniqueness within query
        int count = namedQueryFieldMapper.countByQueryAndField(tenantId, queryCode, request.getFieldCode(), null);
        if (count > 0) {
            throw new MetaServiceException("Field code already exists in query: " + request.getFieldCode());
        }

        NamedQueryField entity = createFieldEntity(tenantId, queryCode, request);
        log.info("Added field to query: queryCode={}, fieldCode={}", queryCode, request.getFieldCode());
        return toFieldDTO(entity);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public NamedQueryFieldDTO updateField(String queryCode, String fieldCode, NamedQueryFieldRequest request) {
        Long tenantId = getCurrentTenantId();

        // Enforce frozen state
        NamedQuery query = namedQueryMapper.findByCode(queryCode);
        if (query != null && query.isFrozen()) {
            throw new MetaServiceException("Cannot update fields: query is " + query.getStatus() + " (frozen)");
        }

        NamedQueryField entity = namedQueryFieldMapper.findByQueryAndField(tenantId, queryCode, fieldCode);
        if (entity == null) {
            throw new MetaServiceException("Field not found: " + queryCode + "." + fieldCode);
        }

        // Apply updates
        if (request.getColumnExpr() != null) {
            entity.setColumnExpr(request.getColumnExpr());
        }
        if (request.getDataType() != null) {
            entity.setDataType(request.getDataType());
        }
        if (request.getOperators() != null) {
            entity.setOperatorList(request.getOperators());
        }
        if (request.getDictCode() != null) {
            entity.setDictCode(request.getDictCode());
        }
        if (request.getSortable() != null) {
            entity.setSortable(request.getSortable());
        }
        if (request.getSearchable() != null) {
            entity.setSearchable(request.getSearchable());
        }

        // UI hints
        if (request.getUiComponent() != null) {
            entity.setUiComponent(request.getUiComponent());
        }
        if (request.getPlaceholder() != null) {
            entity.setPlaceholder(request.getPlaceholder());
        }
        if (request.getDefaultValue() != null) {
            entity.setDefaultValue(request.getDefaultValue());
        }
        if (request.getLinkedField() != null) {
            entity.setLinkedField(request.getLinkedField());
        }
        if (request.getRequired() != null) {
            entity.setRequired(request.getRequired());
        }
        if (request.getDisplayName() != null) {
            entity.setDisplayName(request.getDisplayName());
        }
        if (request.getSortOrder() != null) {
            entity.setSortOrder(request.getSortOrder());
        }
        if (request.getFieldGroup() != null) {
            entity.setFieldGroup(request.getFieldGroup());
        }
        if (request.getUiConfig() != null) {
            entity.setUiConfig(request.getUiConfig());
        }

        entity.setUpdatedAt(Instant.now());
        namedQueryFieldMapper.updateById(entity);

        return toFieldDTO(entity);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public void deleteField(String queryCode, String fieldCode) {
        Long tenantId = getCurrentTenantId();

        // Enforce frozen state
        NamedQuery query = namedQueryMapper.findByCode(queryCode);
        if (query != null && query.isFrozen()) {
            throw new MetaServiceException("Cannot delete fields: query is " + query.getStatus() + " (frozen)");
        }

        NamedQueryField entity = namedQueryFieldMapper.findByQueryAndField(tenantId, queryCode, fieldCode);
        if (entity == null) {
            throw new MetaServiceException("Field not found: " + queryCode + "." + fieldCode);
        }
        namedQueryFieldMapper.deleteById(entity.getId());
        log.info("Deleted field: queryCode={}, fieldCode={}", queryCode, fieldCode);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"namedQuery", "namedQueries", "viewModelFields", "viewModelSummary"}, allEntries = true)
    public NamedQueryFieldBatchResult batchSaveFields(String queryCode, NamedQueryFieldBatchRequest request) {
        Long tenantId = getCurrentTenantId();
        NamedQueryFieldBatchResult result = new NamedQueryFieldBatchResult(
                request.getOperationType(), request.getFields().size());

        // Clear existing fields if requested
        if (Boolean.TRUE.equals(request.getClearExisting())) {
            if (request.getSource() != null) {
                // Source-aware clear: only delete fields with matching source (e.g., PLUGIN)
                namedQueryFieldMapper.deleteByQueryAndSource(tenantId, queryCode, request.getSource());
            } else {
                namedQueryFieldMapper.deleteByQuery(tenantId, queryCode);
            }
        }

        for (NamedQueryFieldRequest fieldReq : request.getFields()) {
            try {
                NamedQueryField existing = namedQueryFieldMapper.findByQueryAndField(
                        tenantId, queryCode, fieldReq.getFieldCode());

                if (existing != null) {
                    if ("set".equals(request.getOperationType()) || "update".equals(request.getOperationType())) {
                        // Update existing
                        existing.setColumnExpr(fieldReq.getColumnExpr());
                        existing.setDataType(fieldReq.getDataType());
                        if (fieldReq.getOperators() != null) {
                            existing.setOperatorList(fieldReq.getOperators());
                        }
                        existing.setDictCode(fieldReq.getDictCode());
                        existing.setSortable(fieldReq.getSortable());
                        existing.setSearchable(fieldReq.getSearchable());
                        existing.setUpdatedAt(Instant.now());
                        namedQueryFieldMapper.updateById(existing);
                        result.addSuccess(existing.getId());
                    } else if (Boolean.TRUE.equals(request.getSkipDuplicates())) {
                        result.addSkipped(existing.getId(), fieldReq.getFieldCode(), "Duplicate field code");
                    } else {
                        result.addFailure(null, fieldReq.getFieldCode(), "Field already exists");
                    }
                } else {
                    NamedQueryField entity = createFieldEntity(tenantId, queryCode, fieldReq, request.getSource());
                    result.addSuccess(entity.getId());
                }
            } catch (Exception e) {
                result.addFailure(null, fieldReq.getFieldCode(), e.getMessage());
            }
        }

        result.complete();
        return result;
    }

    @Override
    @Transactional
    public void markFieldsAsPluginSource(String queryCode) {
        Long tenantId = getCurrentTenantId();
        namedQueryFieldMapper.updateSourceByQuery(tenantId, queryCode, "plugin");
    }

    // ==================== Execution and testing ====================

    @Override
    @Transactional(readOnly = true)
    public NamedQueryTestResult testQuery(String pid, NamedQueryTestRequest request) {
        NamedQueryTestResult result = new NamedQueryTestResult();
        try {
            NamedQuery query = namedQueryMapper.findByPid(pid);
            if (query == null) {
                return NamedQueryTestResult.failure("Named query not found: " + pid);
            }

            result.setQueryId(query.getId());

            // Limit results for test mode
            if (request.getSize() == null || request.getSize() > 10) {
                request.setSize(10);
            }

            PaginationResult<Map<String, Object>> queryResult = executeQuery(query.getCode(), request);

            result.setSuccess(true);
            result.setSyntaxValid(true);
            result.setResultCount(queryResult.getRecords() != null ? queryResult.getRecords().size() : 0);
            result.setSampleData(queryResult.getRecords());
            result.setMessage("Query executed successfully");
            result.complete();
        } catch (Exception e) {
            result.setSuccess(false);
            result.setSyntaxValid(false);
            result.setErrorMessage(e.getMessage());
            result.setMessage("Query execution failed: " + e.getMessage());
            result.complete();
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    @Observed(name = "named_query.execute", contextualName = "named-query-execution")
    public PaginationResult<Map<String, Object>> executeQuery(String code, NamedQueryTestRequest request) {
        // 1. Get query definition
        NamedQuery query = namedQueryMapper.findByCode(code);
        if (query == null) {
            throw new MetaServiceException("Named query not found: " + code);
        }
        if (!query.isExecutable()) {
            throw new MetaServiceException("Named query is not executable (status: " + query.getStatus() + "): " + code);
        }

        Long tenantId = getCurrentTenantId();
        NamedQueryPolicy policy = query.getPolicy() != null ? query.getPolicy() : new NamedQueryPolicy();

        // 2. Rate limit check
        if (policy.isRateLimitEnabled()) {
            if (!rateLimiter.tryAcquire(tenantId, code, policy.getRateLimitPerMinute())) {
                throw new MetaServiceException("Rate limit exceeded for query: " + code
                        + " (max " + policy.getRateLimitPerMinute() + " per minute)");
            }
        }

        // 2b. Connector-type query: delegate to external REST API connector
        if (query.isConnectorType()) {
            return executeConnectorQuery(query, request, policy);
        }

        // 3. Get field whitelist
        List<NamedQueryField> fields = namedQueryFieldMapper.findByQueryCode(tenantId, code);
        Map<String, NamedQueryField> fieldMap = fields.stream()
                .collect(Collectors.toMap(NamedQueryField::getFieldCode, f -> f));

        // 4. Build SELECT columns
        List<String> selectColumns = fields.stream()
                .map(f -> f.getColumnExpr() + " AS " + f.getFieldCode())
                .collect(Collectors.toList());

        if (selectColumns.isEmpty()) {
            selectColumns = Collections.singletonList("*");
        }

        // 5. Build SQL — wrap subquery fromSql in parentheses
        String fromSql = query.getFromSql().trim();
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", selectColumns));
        if (fromSql.toUpperCase().startsWith("SELECT ")) {
            sql.append(" FROM (").append(fromSql).append(") AS _nq");
        } else {
            sql.append(" FROM ").append(fromSql);
        }

        Map<String, Object> params = new HashMap<>();

        // 5b. Merge caller-supplied parameters (e.g. projectId from DataSourceController)
        if (request.getParameters() != null) {
            params.putAll(request.getParameters());
        }

        List<String> whereClauses = new ArrayList<>();

        // 6. Add baseWhere conditions
        if (query.hasBaseWhere()) {
            parseBaseWhere(query.getBaseWhere(), whereClauses, params);
        }

        // 7. Add user conditions (validated against whitelist)
        if (request.getWhereConditions() != null) {
            parseUserConditions(request.getWhereConditions(), fieldMap, whereClauses, params);
        }

        // 8. Add tenant isolation and current user context (override any caller-supplied values)
        params.put("tenantId", tenantId);
        Long userId = getCurrentUserId();
        params.put("currentUserId", userId != null ? userId.toString() : null);
        params.put("currentUserPid", MetaContext.getCurrentUserPid());

        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // 9. Add ORDER BY
        if (request.getOrderConditions() != null) {
            String orderClause = parseOrderConditions(request.getOrderConditions(), fieldMap);
            if (!orderClause.isEmpty()) {
                sql.append(" ORDER BY ").append(orderClause);
            }
        } else if (query.hasDefaultOrder()) {
            String defaultOrderClause = parseDefaultOrder(query.getDefaultOrder(), fieldMap);
            if (!defaultOrderClause.isEmpty()) {
                sql.append(" ORDER BY ").append(defaultOrderClause);
            }
        }

        // 10. Pagination — enforce policy maxRows and sandbox limit
        int effectiveMaxRows = policy.getEffectiveMaxRows(query.getStatusEnum());
        int pageNum = request.getPage() != null ? request.getPage() : 1;
        int pageSize = Math.min(request.getSize() != null ? request.getSize() : 20, effectiveMaxRows);
        int offset = (pageNum - 1) * pageSize;

        // Count total
        // Use WithoutTenant variant: NQ fromSql already contains #{params.tenantId} for tenant isolation.
        // TenantLineInterceptor fails on deeply nested subqueries wrapped by the NQ engine.
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") AS _count_query";
        Long total = dynamicDataMapper.countByQueryWithoutTenant(countSql, params);

        // Add LIMIT/OFFSET
        sql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);

        // 11. Execute (bypass tenant interceptor — tenant isolation is in the NQ SQL itself)
        List<Map<String, Object>> records = dynamicDataMapper.selectByQueryWithoutTenant(sql.toString(), params);

        return PaginationResult.of(records, total, pageNum, pageSize);
    }

    // ==================== Export ====================

    @Override
    @Transactional(readOnly = true)
    public ExportResult exportData(String code, NamedQueryDataExportRequest request) {
        Instant startTime = Instant.now();

        // 1. Get query definition
        NamedQuery query = namedQueryMapper.findByCode(code);
        if (query == null) {
            throw new MetaServiceException("Named query not found: " + code);
        }
        if (!query.isExecutable()) {
            throw new MetaServiceException("Named query is not executable (status: " + query.getStatus() + "): " + code);
        }

        Long tenantId = getCurrentTenantId();

        try {
            // 2. Get field whitelist
            List<NamedQueryField> allFields = namedQueryFieldMapper.findByQueryCode(tenantId, code);
            Map<String, NamedQueryField> fieldMap = allFields.stream()
                    .collect(Collectors.toMap(NamedQueryField::getFieldCode, f -> f));

            // 3. Determine export fields
            List<String> exportFieldCodes;
            if (request.getFields() != null && !request.getFields().isEmpty()) {
                // Validate requested fields are in whitelist
                for (String fc : request.getFields()) {
                    if (!fieldMap.containsKey(fc)) {
                        throw new MetaServiceException("Field not in whitelist: " + fc);
                    }
                }
                exportFieldCodes = request.getFields();
            } else {
                exportFieldCodes = allFields.stream()
                        .map(NamedQueryField::getFieldCode)
                        .collect(Collectors.toList());
            }

            // 4. Build SELECT columns
            List<String> selectColumns = exportFieldCodes.stream()
                    .map(fc -> fieldMap.get(fc).getColumnExpr() + " AS " + fc)
                    .collect(Collectors.toList());

            // 5. Build SQL — wrap subquery fromSql in parentheses
            String exportFromSql = query.getFromSql().trim();
            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(String.join(", ", selectColumns));
            if (exportFromSql.toUpperCase().startsWith("SELECT ")) {
                sql.append(" FROM (").append(exportFromSql).append(") AS _nq");
            } else {
                sql.append(" FROM ").append(exportFromSql);
            }

            Map<String, Object> params = new HashMap<>();
            List<String> whereClauses = new ArrayList<>();

            if (query.hasBaseWhere()) {
                parseBaseWhere(query.getBaseWhere(), whereClauses, params);
            }

            if (request.getWhereConditions() != null) {
                parseUserConditions(request.getWhereConditions(), fieldMap, whereClauses, params);
            }

            params.put("tenantId", tenantId);
            Long userId = getCurrentUserId();
            params.put("currentUserId", userId != null ? userId.toString() : null);

            if (!whereClauses.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
            }

            // 6. ORDER BY
            if (request.getOrderConditions() != null) {
                String orderClause = parseOrderConditions(request.getOrderConditions(), fieldMap);
                if (!orderClause.isEmpty()) {
                    sql.append(" ORDER BY ").append(orderClause);
                }
            } else if (query.hasDefaultOrder()) {
                String defaultOrderClause = parseDefaultOrder(query.getDefaultOrder(), fieldMap);
                if (!defaultOrderClause.isEmpty()) {
                    sql.append(" ORDER BY ").append(defaultOrderClause);
                }
            }

            // 7. LIMIT — enforce policy exportMaxRows
            NamedQueryPolicy exportPolicy = query.getPolicy() != null ? query.getPolicy() : new NamedQueryPolicy();
            int policyExportMax = exportPolicy.getExportMaxRows() != null ? exportPolicy.getExportMaxRows() : 50000;
            int limit = request.getLimit() != null ? Math.min(request.getLimit(), policyExportMax) : Math.min(10000, policyExportMax);
            sql.append(" LIMIT ").append(limit);

            // 8. Execute query
            List<Map<String, Object>> data = dynamicDataMapper.selectByQuery(sql.toString(), params);

            // 9. Generate export file
            DataExportRequest.ExportFormat format = request.getFormat() != null
                    ? request.getFormat() : DataExportRequest.ExportFormat.EXCEL;
            String fileName = request.getFileName() != null
                    ? request.getFileName()
                    : code + "_export_" + System.currentTimeMillis();

            java.nio.file.Path tempFile;
            switch (format) {
                case EXCEL:
                    tempFile = exportAsExcel(data, exportFieldCodes, fileName, request.getIncludeHeader());
                    break;
                case JSON:
                    tempFile = exportAsJson(data, exportFieldCodes, fileName);
                    break;
                case CSV:
                default:
                    tempFile = exportAsCsv(data, exportFieldCodes, fileName, request.getIncludeHeader());
                    break;
            }

            long fileSize = java.nio.file.Files.size(tempFile);
            return ExportResult.builder()
                    .success(true)
                    .filePath(tempFile.toString())
                    .recordCount((long) data.size())
                    .fileSize(fileSize)
                    .format(format.name())
                    .exportTime(startTime)
                    .build();

        } catch (MetaServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Export failed for named query {}: {}", code, e.getMessage(), e);
            return ExportResult.builder()
                    .success(false)
                    .errorMessage("Export failed: " + e.getMessage())
                    .format(request.getFormat() != null ? request.getFormat().name() : "excel")
                    .build();
        }
    }

    private java.nio.file.Path exportAsExcel(List<Map<String, Object>> data, List<String> fields,
                                              String fileName, Boolean includeHeader) throws java.io.IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(fileName, ".xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("Data");

            org.apache.poi.xssf.usermodel.XSSFFont defaultFont = workbook.createFont();
            defaultFont.setFontName("Arial Unicode MS");
            defaultFont.setFontHeightInPoints((short) 11);

            org.apache.poi.xssf.usermodel.XSSFCellStyle defaultStyle = workbook.createCellStyle();
            defaultStyle.setFont(defaultFont);

            int rowNum = 0;

            if (!Boolean.FALSE.equals(includeHeader)) {
                org.apache.poi.xssf.usermodel.XSSFRow headerRow = sheet.createRow(rowNum++);
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
                    cell.setCellValue(fields.get(i));
                    cell.setCellStyle(headerStyle);
                }
            }

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
                        } else {
                            cell.setCellValue(val.toString());
                        }
                    }
                }
            }

            for (int i = 0; i < fields.size(); i++) {
                sheet.autoSizeColumn(i);
                int currentWidth = sheet.getColumnWidth(i);
                if (currentWidth < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
        }
        return tempFile;
    }

    private java.nio.file.Path exportAsCsv(List<Map<String, Object>> data, List<String> fields,
                                             String fileName, Boolean includeHeader) throws java.io.IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(fileName, ".csv");
        try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(tempFile, java.nio.charset.StandardCharsets.UTF_8)) {
            if (!Boolean.FALSE.equals(includeHeader)) {
                writer.write(String.join(",", fields));
                writer.newLine();
            }
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

    private java.nio.file.Path exportAsJson(List<Map<String, Object>> data, List<String> fields,
                                             String fileName) throws java.io.IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(fileName, ".json");
        List<Map<String, Object>> filtered = data.stream()
                .map(row -> {
                    Map<String, Object> filteredRow = new LinkedHashMap<>();
                    for (String field : fields) {
                        filteredRow.put(field, row.get(field));
                    }
                    return filteredRow;
                })
                .collect(Collectors.toList());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), filtered);
        return tempFile;
    }

    // ==================== Validation ====================

    @Override
    public NamedQueryValidationResult validate(NamedQueryValidationRequest request) {
        NamedQueryValidationResult result = new NamedQueryValidationResult(request.getValidationType());

        // Validate SQL
        if (Boolean.TRUE.equals(request.getValidateSql())) {
            try {
                validateFromSql(request.getFromSql());
            } catch (MetaServiceException e) {
                result.addError("sql_invalid", e.getMessage(), "fromSql");
            }
        }

        // Validate fields
        if (Boolean.TRUE.equals(request.getValidateFields()) && request.getFields() != null) {
            for (NamedQueryFieldRequest field : request.getFields()) {
                if (field.getOperators() != null) {
                    for (String op : field.getOperators()) {
                        if (!ALLOWED_OPERATORS.contains(op.toLowerCase())) {
                            result.addError("invalid_operator",
                                    "Unsupported operator: " + op,
                                    field.getFieldCode() + ".operators");
                        }
                    }
                }
            }
        }

        result.complete();
        return result;
    }

    // ==================== FieldUsage integration ====================

    @Override
    public List<String> getQueryCodesByFieldCode(String fieldCode) {
        // Find all named query fields that reference the given field code
        QueryWrapper<NamedQueryField> wrapper = new QueryWrapper<>();
        wrapper.eq("field_code", fieldCode);
        List<NamedQueryField> matchingFields = namedQueryFieldMapper.selectList(wrapper);
        return matchingFields.stream()
                .map(NamedQueryField::getQueryCode)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public int countByFieldCode(String fieldCode) {
        return getQueryCodesByFieldCode(fieldCode).size();
    }

    // ==================== Version management ====================

    @Override
    public List<NamedQueryVersionDTO> getVersions(String queryCode) {
        List<NamedQueryVersion> versions = namedQueryVersionMapper.findByQueryCode(queryCode);
        return versions.stream()
                .map(this::toVersionDTO)
                .collect(Collectors.toList());
    }

    @Override
    public NamedQueryVersionDTO getVersion(String queryCode, int versionNo) {
        NamedQueryVersion version = namedQueryVersionMapper.findByQueryCodeAndVersion(queryCode, versionNo);
        if (version == null) {
            throw new MetaServiceException("Version not found: " + queryCode + "@" + versionNo);
        }
        return toVersionDTO(version);
    }

    // ==================== Private helpers ====================

    /**
     * Execute a connector-type NamedQuery by delegating to an external REST API.
     * The connector response is expected to contain a "records" or "data" list.
     * Pagination is applied client-side on the returned list.
     */
    @SuppressWarnings("unchecked")
    private PaginationResult<Map<String, Object>> executeConnectorQuery(
            NamedQuery query, NamedQueryTestRequest request, NamedQueryPolicy policy) {
        Map<String, Object> params = request.getParameters() != null
                ? new HashMap<>(request.getParameters())
                : new HashMap<>();

        // Add pagination hints for connectors that support them
        int pageNum = request.getPage() != null ? request.getPage() : 1;
        int pageSize = request.getSize() != null
                ? Math.min(request.getSize(), policy.getEffectiveMaxRows(query.getStatusEnum()))
                : 20;
        params.put("page", pageNum);
        params.put("pageSize", pageSize);
        params.put("offset", (pageNum - 1) * pageSize);

        try {
            Map<String, Object> response = apiConnectorService.invoke(
                    query.getConnectorPid(), query.getConnectorEndpointCode(), params);

            // Extract records from common response shapes: {records:[...]}, {data:[...]}, or list root
            List<Map<String, Object>> records;
            if (response.containsKey("records") && response.get("records") instanceof List) {
                records = (List<Map<String, Object>>) response.get("records");
            } else if (response.containsKey("data") && response.get("data") instanceof List) {
                records = (List<Map<String, Object>>) response.get("data");
            } else {
                // Treat entire response as single record
                records = List.of(response);
            }

            // Extract total from response if present
            long total = records.size();
            if (response.containsKey("total") && response.get("total") instanceof Number) {
                total = ((Number) response.get("total")).longValue();
            } else if (response.containsKey("totalCount") && response.get("totalCount") instanceof Number) {
                total = ((Number) response.get("totalCount")).longValue();
            }

            PaginationResult<Map<String, Object>> result = new PaginationResult<>();
            result.setRecords(records);
            result.setTotal(total);
            result.setPage(pageNum);
            result.setPageSize(pageSize);
            return result;
        } catch (Exception e) {
            log.error("Connector query failed: connectorPid={}, endpoint={}, error={}",
                    query.getConnectorPid(), query.getConnectorEndpointCode(), e.getMessage());
            throw new MetaServiceException("Connector query failed: " + e.getMessage(), e);
        }
    }

    private void validateFromSql(String fromSql) {
        if (fromSql == null || fromSql.trim().isEmpty()) {
            throw new MetaServiceException("FROM SQL cannot be empty");
        }
        String trimmed = fromSql.trim();

        // Detect SELECT subquery — either bare "SELECT ..." or parenthesized "(SELECT ...) alias"
        String sqlToValidate = trimmed;
        if (trimmed.startsWith("(")) {
            // Unwrap outermost parentheses: "(SELECT ... FROM ...) q" → "SELECT ... FROM ..."
            int depth = 0;
            int closeIdx = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) == '(') depth++;
                if (trimmed.charAt(i) == ')') depth--;
                if (depth == 0) { closeIdx = i; break; }
            }
            if (closeIdx > 0) {
                sqlToValidate = trimmed.substring(1, closeIdx).trim();
            }
        }

        if (sqlToValidate.toUpperCase().startsWith("SELECT ")) {
            // Full SELECT statement — validate for forbidden DML/DDL keywords
            // (allows nested subqueries, window functions, COALESCE, CASE, etc.)
            try {
                SqlSafetyUtils.validateSelectOnlySql(sqlToValidate);
            } catch (IllegalArgumentException e) {
                throw new MetaServiceException("FROM SQL contains dangerous patterns: " + e.getMessage());
            }
        } else {
            // Table name or expression — validate as SQL fragment (strict: no nested parens)
            try {
                SqlSafetyUtils.validateSqlFragment(trimmed);
            } catch (IllegalArgumentException e) {
                throw new MetaServiceException("FROM SQL contains dangerous patterns: " + e.getMessage());
            }
        }
    }

    private NamedQueryField createFieldEntity(Long tenantId, String queryCode, NamedQueryFieldRequest request) {
        return createFieldEntity(tenantId, queryCode, request, null);
    }

    private NamedQueryField createFieldEntity(Long tenantId, String queryCode, NamedQueryFieldRequest request, String source) {
        NamedQueryField entity = new NamedQueryField(tenantId, queryCode,
                request.getFieldCode(), request.getColumnExpr(), request.getDataType());

        if (request.getOperators() != null) {
            entity.setOperatorList(request.getOperators());
        }
        entity.setDictCode(request.getDictCode());
        entity.setSortable(request.getSortable() != null ? request.getSortable() : false);
        entity.setSearchable(request.getSearchable() != null ? request.getSearchable() : true);

        // UI hints
        entity.setUiComponent(request.getUiComponent() != null ? request.getUiComponent() : "text");
        entity.setPlaceholder(request.getPlaceholder());
        entity.setDefaultValue(request.getDefaultValue());
        entity.setLinkedField(request.getLinkedField());
        entity.setRequired(request.getRequired() != null ? request.getRequired() : false);
        entity.setDisplayName(request.getDisplayName());
        entity.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        entity.setFieldGroup(request.getFieldGroup());
        entity.setUiConfig(request.getUiConfig());
        if (source != null) {
            entity.setSource(source);
        }

        namedQueryFieldMapper.insert(entity);
        return entity;
    }

    private void parseBaseWhere(JsonNode baseWhere, List<String> whereClauses, Map<String, Object> params) {
        if (baseWhere == null || !baseWhere.isArray()) {
            return;
        }
        int idx = 0;
        for (JsonNode condition : baseWhere) {
            String field = condition.has("field") ? condition.get("field").asText() : null;
            String operator = condition.has("operator") ? condition.get("operator").asText() : "eq";
            JsonNode valueNode = condition.has("value") ? condition.get("value") : null;

            if (field == null || valueNode == null) {
                continue;
            }

            // Validate field name to prevent SQL injection via baseWhere config
            SqlSafetyUtils.validateIdentifier(field, "baseWhere field");

            if (!ALLOWED_OPERATORS.contains(operator.toLowerCase())) {
                throw new MetaServiceException("Unsupported operator in baseWhere: " + operator);
            }

            String paramKey = "bw_" + idx;
            String clause = buildWhereClause(field, operator, paramKey, valueNode, params);
            if (clause != null) {
                whereClauses.add(clause);
            }
            idx++;
        }
    }

    private void parseUserConditions(JsonNode conditions, Map<String, NamedQueryField> fieldMap,
                                     List<String> whereClauses, Map<String, Object> params) {
        if (conditions == null || !conditions.isArray()) {
            return;
        }
        int idx = 0;
        for (JsonNode condition : conditions) {
            String fieldCode = condition.has("field") ? condition.get("field").asText() : null;
            String operator = condition.has("operator") ? condition.get("operator").asText() : "eq";
            JsonNode valueNode = condition.has("value") ? condition.get("value") : null;

            if (fieldCode == null) {
                continue;
            }

            // Validate field is in whitelist
            NamedQueryField fieldDef = fieldMap.get(fieldCode);
            if (fieldDef == null) {
                throw new MetaServiceException("Field not allowed: " + fieldCode);
            }

            // Validate operator is allowed
            if (fieldDef.hasOperators() && !fieldDef.supportsOperator(operator)) {
                throw new MetaServiceException("Operator not allowed for field " + fieldCode + ": " + operator);
            }

            String columnExpr = fieldDef.getColumnExpr();
            // Validate columnExpr to prevent SQL injection via admin-configured field definitions
            SqlSafetyUtils.validateSqlFragment(columnExpr);
            String paramKey = "uc_" + idx;
            String clause = buildWhereClause(columnExpr, operator, paramKey, valueNode, params);
            if (clause != null) {
                whereClauses.add(clause);
            }
            idx++;
        }
    }

    private String buildWhereClause(String column, String operator, String paramKey,
                                    JsonNode valueNode, Map<String, Object> params) {
        String op = operator.toLowerCase();
        switch (op) {
            case "eq":
                params.put(paramKey, extractValue(valueNode));
                return column + " = #{params." + paramKey + "}";
            case "ne":
                params.put(paramKey, extractValue(valueNode));
                return column + " != #{params." + paramKey + "}";
            case "gt":
                params.put(paramKey, extractValue(valueNode));
                return column + " > #{params." + paramKey + "}";
            case "gte":
                params.put(paramKey, extractValue(valueNode));
                return column + " >= #{params." + paramKey + "}";
            case "lt":
                params.put(paramKey, extractValue(valueNode));
                return column + " < #{params." + paramKey + "}";
            case "lte":
                params.put(paramKey, extractValue(valueNode));
                return column + " <= #{params." + paramKey + "}";
            case "like":
            case "contains":
                params.put(paramKey, "%" + extractValue(valueNode) + "%");
                return column + " LIKE #{params." + paramKey + "}";
            case "ilike":
                params.put(paramKey, "%" + extractValue(valueNode) + "%");
                return column + " ILIKE #{params." + paramKey + "}";
            case "starts_with":
                params.put(paramKey, extractValue(valueNode) + "%");
                return column + " LIKE #{params." + paramKey + "}";
            case "ends_with":
                params.put(paramKey, "%" + extractValue(valueNode));
                return column + " LIKE #{params." + paramKey + "}";
            case "is_null":
                return column + " IS NULL";
            case "is_not_null":
                return column + " IS NOT NULL";
            case "in":
                if (valueNode.isArray()) {
                    List<Object> values = new ArrayList<>();
                    for (JsonNode v : valueNode) {
                        values.add(extractValue(v));
                    }
                    params.put(paramKey, values);
                    // Build IN clause with indexed params
                    StringBuilder inClause = new StringBuilder(column + " IN (");
                    for (int i = 0; i < values.size(); i++) {
                        String indexedKey = paramKey + "_" + i;
                        params.put(indexedKey, values.get(i));
                        if (i > 0) inClause.append(", ");
                        inClause.append("#{params.").append(indexedKey).append("}");
                    }
                    inClause.append(")");
                    return inClause.toString();
                }
                return null;
            case "not_in":
                if (valueNode.isArray()) {
                    List<Object> values = new ArrayList<>();
                    for (JsonNode v : valueNode) {
                        values.add(extractValue(v));
                    }
                    StringBuilder notInClause = new StringBuilder(column + " NOT IN (");
                    for (int i = 0; i < values.size(); i++) {
                        String indexedKey = paramKey + "_" + i;
                        params.put(indexedKey, values.get(i));
                        if (i > 0) notInClause.append(", ");
                        notInClause.append("#{params.").append(indexedKey).append("}");
                    }
                    notInClause.append(")");
                    return notInClause.toString();
                }
                return null;
            case "between":
                if (valueNode.isArray() && valueNode.size() >= 2) {
                    params.put(paramKey + "_from", extractValue(valueNode.get(0)));
                    params.put(paramKey + "_to", extractValue(valueNode.get(1)));
                    return column + " BETWEEN #{params." + paramKey + "_from} AND #{params." + paramKey + "_to}";
                }
                return null;
            default:
                throw new MetaServiceException("Unsupported operator: " + operator);
        }
    }

    private Object extractValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            if (node.isInt()) return node.intValue();
            if (node.isLong()) return node.longValue();
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private String parseOrderConditions(JsonNode orderConditions, Map<String, NamedQueryField> fieldMap) {
        if (orderConditions == null || !orderConditions.isArray()) {
            return "";
        }
        List<String> orderParts = new ArrayList<>();
        for (JsonNode order : orderConditions) {
            String fieldCode = order.has("field") ? order.get("field").asText() : null;
            String direction = order.has("direction") ? order.get("direction").asText() : "asc";

            if (fieldCode == null) continue;

            NamedQueryField fieldDef = fieldMap.get(fieldCode);
            if (fieldDef == null || !fieldDef.checkSortable()) {
                continue; // Skip non-sortable fields silently
            }

            String dir = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
            orderParts.add(fieldDef.getColumnExpr() + " " + dir);
        }
        return String.join(", ", orderParts);
    }

    private String parseDefaultOrder(JsonNode defaultOrder, Map<String, NamedQueryField> fieldMap) {
        // defaultOrder can be array format: [{"field":"name","direction":"asc"}]
        if (defaultOrder.isArray()) {
            return parseOrderConditions(defaultOrder, fieldMap);
        }
        // Or object format: {"field":"created_at","direction":"desc"}
        if (defaultOrder.isObject() && defaultOrder.has("field")) {
            String fieldCode = defaultOrder.get("field").asText();
            String direction = defaultOrder.has("direction") ? defaultOrder.get("direction").asText() : "asc";
            NamedQueryField fieldDef = fieldMap.get(fieldCode);
            if (fieldDef != null && fieldDef.checkSortable()) {
                String dir = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
                return fieldDef.getColumnExpr() + " " + dir;
            }
        }
        return "";
    }

    private String mapSortColumn(String sortBy) {
        switch (sortBy) {
            case "createdAt": return "created_at";
            case "updatedAt": return "updated_at";
            case "code": return "code";
            case "title": return "title";
            case "status": return "status";
            default: return "created_at";
        }
    }

    private NamedQueryDTO toDTO(NamedQuery entity, boolean includeFields) {
        NamedQueryDTO dto = new NamedQueryDTO();
        dto.setId(entity.getId());
        dto.setPid(entity.getPid());
        dto.setTenantId(entity.getTenantId());
        dto.setCode(entity.getCode());
        dto.setTitle(entity.getTitle());
        dto.setDescription(entity.getDescription());
        dto.setFromSql(entity.getFromSql());
        dto.setConnectorPid(entity.getConnectorPid());
        dto.setConnectorEndpointCode(entity.getConnectorEndpointCode());
        dto.setBaseWhere(entity.getBaseWhere());
        dto.setDefaultOrder(entity.getDefaultOrder());
        dto.setStatus(entity.getStatus());
        dto.setPublishedAt(toLocalDateTime(entity.getPublishedAt()));
        dto.setPublishedBy(entity.getPublishedBy());
        dto.setDeprecatedAt(toLocalDateTime(entity.getDeprecatedAt()));
        dto.setCurrentVersion(entity.getCurrentVersion());
        dto.setPolicy(entity.getPolicy());
        dto.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
        dto.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));

        // Computed properties
        dto.setQueryType(entity.getQueryType());
        dto.setIsComplexQuery(entity.isComplexQuery());
        dto.setDisplayName(entity.getDisplayName());
        dto.setExecutable(entity.isExecutable());
        dto.setEditable(entity.isEditable());
        dto.setFrozen(entity.isFrozen());
        dto.setEnabled(entity.isEnabled());
        dto.setHasBaseWhere(entity.hasBaseWhere());
        dto.setHasDefaultOrder(entity.hasDefaultOrder());
        dto.setSummary(entity.getSummary());

        if (includeFields) {
            Long tenantId = entity.getTenantId();
            List<NamedQueryField> fields = namedQueryFieldMapper.findByQueryCode(tenantId, entity.getCode());
            dto.setFields(fields.stream().map(this::toFieldDTO).collect(Collectors.toList()));
            dto.setFieldCount(fields.size());
        }

        return dto;
    }

    private NamedQueryFieldDTO toFieldDTO(NamedQueryField entity) {
        NamedQueryFieldDTO dto = new NamedQueryFieldDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setQueryCode(entity.getQueryCode());
        dto.setFieldCode(entity.getFieldCode());
        dto.setColumnExpr(entity.getColumnExpr());
        dto.setDataType(entity.getDataType());
        dto.setOperators(entity.getOperators());
        dto.setDictCode(entity.getDictCode());
        dto.setSortable(entity.getSortable());
        dto.setSearchable(entity.getSearchable());
        // UI hints
        dto.setUiComponent(entity.getUiComponent());
        dto.setPlaceholder(entity.getPlaceholder());
        dto.setDefaultValue(entity.getDefaultValue());
        dto.setLinkedField(entity.getLinkedField());
        dto.setRequired(entity.getRequired());
        dto.setDisplayName(entity.getDisplayName());
        dto.setSortOrder(entity.getSortOrder());
        dto.setFieldGroup(entity.getFieldGroup());
        dto.setUiConfig(entity.getUiConfig());
        dto.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
        dto.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));

        // Computed properties
        dto.setOperatorList(entity.getOperatorList());
        dto.setFullFieldCode(entity.getFullFieldCode());
        dto.setHasDict(entity.hasDict());
        dto.setHasOperators(entity.hasOperators());
        dto.setDefaultOperators(entity.getDefaultOperators());
        dto.setSummary(entity.getSummary());

        return dto;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void createVersionSnapshot(NamedQuery query, Instant now) {
        int nextVersion = namedQueryVersionMapper.getMaxVersionNo(query.getCode()) + 1;

        // Snapshot current fields as JSON
        Long tenantId = query.getTenantId();
        List<NamedQueryField> fields = namedQueryFieldMapper.findByQueryCode(tenantId, query.getCode());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode fieldsSnapshot;
        try {
            fieldsSnapshot = mapper.valueToTree(fields.stream()
                    .map(f -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("fieldCode", f.getFieldCode());
                        m.put("columnExpr", f.getColumnExpr());
                        m.put("dataType", f.getDataType());
                        m.put("operators", f.getOperators());
                        m.put("sortable", f.getSortable());
                        m.put("searchable", f.getSearchable());
                        m.put("uiComponent", f.getUiComponent());
                        m.put("displayName", f.getDisplayName());
                        m.put("sortOrder", f.getSortOrder());
                        return m;
                    })
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            fieldsSnapshot = mapper.createArrayNode();
        }

        NamedQueryVersion version = new NamedQueryVersion();
        version.setPid(UlidGenerator.generate());
        version.setTenantId(tenantId);
        version.setQueryCode(query.getCode());
        version.setVersionNo(nextVersion);
        version.setFromSql(query.getFromSql());
        version.setBaseWhere(query.getBaseWhere());
        version.setDefaultOrder(query.getDefaultOrder());
        version.setFieldsSnapshot(fieldsSnapshot);
        version.setPolicy(query.getPolicy());
        version.setDescription(query.getDescription());
        version.setStatus(StatusConstants.PUBLISHED);
        version.setPublishedAt(now);
        version.setPublishedBy(getCurrentUserId());
        version.setCreatedAt(now);

        namedQueryVersionMapper.insert(version);

        // Update current_version on the query
        query.setCurrentVersion(nextVersion);

        log.info("Created version snapshot: code={}, version={}", query.getCode(), nextVersion);
    }

    private NamedQueryVersionDTO toVersionDTO(NamedQueryVersion entity) {
        NamedQueryVersionDTO dto = new NamedQueryVersionDTO();
        dto.setPid(entity.getPid());
        dto.setQueryCode(entity.getQueryCode());
        dto.setVersionNo(entity.getVersionNo());
        dto.setFromSql(entity.getFromSql());
        dto.setBaseWhere(entity.getBaseWhere());
        dto.setDefaultOrder(entity.getDefaultOrder());
        dto.setFieldsSnapshot(entity.getFieldsSnapshot());
        dto.setPolicy(entity.getPolicy());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setPublishedAt(toLocalDateTime(entity.getPublishedAt()));
        dto.setPublishedBy(entity.getPublishedBy());
        dto.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
        return dto;
    }
}
