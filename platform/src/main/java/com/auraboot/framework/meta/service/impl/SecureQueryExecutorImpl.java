package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.security.SqlInjectionProtector;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 安全查询执行器实现类
 *
 * <p>已迁移到Permission系统进行权限检查
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecureQueryExecutorImpl implements SecureQueryExecutor {

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    private final QueryBuilderService queryBuilderService;
    private final MetaModelService metaModelService;
    private final UserPermissionService userPermissionService;
    private final SqlInjectionProtector sqlInjectionProtector;
    private final QueryAuditService queryAuditService;
    private final com.auraboot.framework.meta.mapper.DynamicDataMapper dynamicDataMapper;
    private final DataPermissionEngine dataPermissionEngine;

    // ==================== 核心查询执行方法 ====================

    @Override
    @Observed(name = "secure_query.execute", contextualName = "secure-query-executor")
    public <T> PaginationResult<T> executeSecureQuery(SecureQueryRequest request) {
        log.info("执行安全查询: modelCode={}, queryType={}, userId={}", 
                logSafe(request.getModelCode()), logSafe(request.getQueryType()), request.getUserId());
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 验证查询安全性
            QuerySecurityValidationResult securityResult = validateQuerySecurity(request);
            if (!securityResult.getValid()) {
                throw new SecurityException("查询安全验证失败: " + String.join(", ", securityResult.getErrors()));
            }
            
            // 2. 检查查询权限
            QueryAccessCheckResult permissionResult = checkQueryPermissions(request);
            if (!permissionResult.getHasAccess()) {
                throw new SecurityException("查询权限检查失败: 用户无权限执行此查询");
            }
            
            // 3. 验证查询复杂度
            QueryComplexityValidationResult complexityResult = validateQueryComplexity(request);
            if (!complexityResult.getValid()) {
                throw new IllegalArgumentException("查询复杂度超出限制: " + complexityResult.getReason());
            }
            
            // 4. 检查缓存
            if (Boolean.TRUE.equals(request.getEnableCache())) {
                PaginationResult<T> cachedResult = getQueryCache(request);
                if (cachedResult != null) {
                    log.debug("返回缓存结果: modelCode={}", logSafe(request.getModelCode()));
                    return cachedResult;
                }
            }
            
            // 5. 构建安全查询
            QueryBuilderService.QueryBuilder queryBuilder = buildSecureQuery(request);
            
            // 6. 执行查询
            PaginationResult<T> result = executeQuery(queryBuilder, request);
            
            // 7. 应用数据脱敏
            if (Boolean.TRUE.equals(request.getEnableDataMasking())) {
                result = applyDataMasking(result, request);
            }
            
            // 8. 应用字段权限过滤
            result = applyFieldPermissionFilter(result, request);
            
            // 9. 设置缓存
            if (Boolean.TRUE.equals(request.getEnableCache())) {
                setQueryCache(request, result);
            }
            
            // 10. 记录审计日志
            long executionTime = System.currentTimeMillis() - startTime;
            if (Boolean.TRUE.equals(request.getEnableAudit())) {
                logQueryAudit(request, result, executionTime);
            }
            
            log.info("安全查询执行完成: modelCode={}, resultCount={}, executionTime={}ms", 
                    logSafe(request.getModelCode()), result.getTotal(), executionTime);
            
            return result;
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            logQueryError(request, e, executionTime);
            throw e;
        }
    }

    @Override
    public <T> List<T> executeSecureQueryList(SecureQueryRequest request) {
        PaginationResult<T> result = executeSecureQuery(request);
        return result.getRecords();
    }

    @Override
    public <T> T executeSecureQuerySingle(SecureQueryRequest request) {
        // 设置限制为1条记录
        if (request.getPagination() == null) {
            request.setPagination(new PaginationRequest());
        }
        request.getPagination().setPageSize(1);
        
        List<T> results = executeSecureQueryList(request);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public Long executeSecureCount(SecureQueryRequest request) {
        log.debug("执行安全计数查询: modelCode={}", logSafe(request.getModelCode()));
        
        // 创建计数查询请求
        SecureQueryRequest countRequest = new SecureQueryRequest();
        countRequest.setModelCode(request.getModelCode());
        countRequest.setQueryType(QueryType.SELECT_COUNT);
        countRequest.setConditions(request.getConditions());
        countRequest.setUserId(request.getUserId());
        countRequest.setTenantId(request.getTenantId());
        countRequest.setEnableCache(request.getEnableCache());
        countRequest.setEnableAudit(false); // 计数查询不记录审计日志
        
        // 执行查询
        PaginationResult<Map<String, Object>> result = executeSecureQuery(countRequest);
        
        // 提取计数结果
        if (result.getRecords() != null && !result.getRecords().isEmpty()) {
            Map<String, Object> countResult = result.getRecords().get(0);
            Object count = countResult.get("count");
            return count instanceof Number ? ((Number) count).longValue() : 0L;
        }
        
        return 0L;
    }

    @Override
    public Map<String, Object> executeSecureAggregate(SecureQueryRequest request) {
        log.debug("执行安全聚合查询: modelCode={}", logSafe(request.getModelCode()));
        
        if (request.getAggregateRequest() == null) {
            throw new IllegalArgumentException("聚合查询请求不能为空");
        }
        
        PaginationResult<Map<String, Object>> result = executeSecureQuery(request);
        
        if (result.getRecords() != null && !result.getRecords().isEmpty()) {
            return result.getRecords().get(0);
        }
        
        return new HashMap<>();
    }

    // ==================== 查询验证方法 ====================

    @Override
    public QuerySecurityValidationResult validateQuerySecurity(SecureQueryRequest request) {
        log.debug("验证查询安全性: modelCode={}", logSafe(request.getModelCode()));
        
        // 使用SQL注入防护器验证查询条件
        return sqlInjectionProtector.validateQueryConditions(request.getConditions());
    }

    @Override
    public QueryAccessCheckResult checkQueryPermissions(SecureQueryRequest request) {
        log.debug("检查查询权限: modelCode={}, userId={}", logSafe(request.getModelCode()), request.getUserId());
        
        QueryAccessCheckResult result = new QueryAccessCheckResult();
        result.setDetails(new ArrayList<>());
        result.setDeniedFields(new ArrayList<>());
        result.setDeniedOperations(new ArrayList<>());
        result.setAccessContext(new HashMap<>());
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 检查模型级权限
            boolean hasModelPermission = checkModelPermission(request, result);
            if (!hasModelPermission) {
                result.setHasAccess(false);
                result.setCheckTimeMs(System.currentTimeMillis() - startTime);
                logPermissionDenied(request, "模型级权限检查失败");
                return result;
            }
            
            // 2. 检查字段级权限
            List<String> deniedFields = checkFieldPermissions(request, result);
            result.setDeniedFields(deniedFields);
            
            // 3. 检查操作权限
            List<String> deniedOperations = checkOperationPermissions(request, result);
            result.setDeniedOperations(deniedOperations);
            
            // 4. 综合判断是否有权限
            // 如果模型权限通过,即使有部分字段或操作被拒绝,也认为有基本权限
            // 具体的字段和操作限制会在后续的过滤中应用
            result.setHasAccess(true);
            
            // 5. 记录权限检查详情
            QueryAccessCheckResult.AccessCheckDetail modelDetail = 
                new QueryAccessCheckResult.AccessCheckDetail();
            modelDetail.setResource(request.getModelCode());
            modelDetail.setOperation(convertQueryTypeToAction(request.getQueryType()));
            modelDetail.setAllowed(true);
            modelDetail.setReason("模型权限检查通过");
            result.getDetails().add(modelDetail);
            
            if (!deniedFields.isEmpty()) {
                QueryAccessCheckResult.AccessCheckDetail fieldDetail = 
                    new QueryAccessCheckResult.AccessCheckDetail();
                fieldDetail.setResource(request.getModelCode());
                fieldDetail.setOperation("field_access");
                fieldDetail.setAllowed(false);
                fieldDetail.setReason(String.format("受限字段数量: %d", deniedFields.size()));
                result.getDetails().add(fieldDetail);
            }
            
            if (!deniedOperations.isEmpty()) {
                QueryAccessCheckResult.AccessCheckDetail opDetail = 
                    new QueryAccessCheckResult.AccessCheckDetail();
                opDetail.setResource(request.getModelCode());
                opDetail.setOperation(String.join(",", deniedOperations));
                opDetail.setAllowed(false);
                opDetail.setReason(String.format("受限操作数量: %d", deniedOperations.size()));
                result.getDetails().add(opDetail);
            }
            
            log.debug("权限检查完成: modelCode={}, userId={}, hasAccess={}, deniedFields={}, deniedOperations={}", 
                     logSafe(request.getModelCode()), request.getUserId(), result.getHasAccess(),
                     deniedFields.size(), deniedOperations.size());
            
        } catch (Exception e) {
            log.error("权限检查失败: modelCode={}, userId={}, error={}", 
                     logSafe(request.getModelCode()), request.getUserId(), logSafe(e.getMessage()), e);
            result.setHasAccess(false);
            
            QueryAccessCheckResult.AccessCheckDetail errorDetail = 
                new QueryAccessCheckResult.AccessCheckDetail();
            errorDetail.setResource(request.getModelCode());
            errorDetail.setOperation("Permission_CHECK");
            errorDetail.setAllowed(false);
            errorDetail.setReason("权限检查异常: " + e.getMessage());
            result.getDetails().add(errorDetail);
            
            logPermissionError(request, e);
        }
        
        result.setCheckTimeMs(System.currentTimeMillis() - startTime);
        return result;
    }
    
    /**
     * 检查模型级权限
     *
     * <p>使用Permission系统进行权限检查
     */
    private boolean checkModelPermission(SecureQueryRequest request, QueryAccessCheckResult result) {
        String action = convertQueryTypeToAction(request.getQueryType());
        // 构建Permission编码: MODEL.{modelCode}.{action}
        String permissionCode = "model." + request.getModelCode() + "." + action.toLowerCase();

        boolean hasPermission = userPermissionService.hasPermission(request.getUserId(), permissionCode);

        result.getAccessContext().put("modelPermissionCheck", "permission");
        result.getAccessContext().put("modelCode", request.getModelCode());
        result.getAccessContext().put("action", action);
        result.getAccessContext().put("permissionCode", permissionCode);
        result.getAccessContext().put("hasPermission", hasPermission);

        if (!hasPermission) {
            log.warn("用户无权访问模型: modelCode={}, userId={}, permissionCode={}",
                logSafe(request.getModelCode()), request.getUserId(), logSafe(permissionCode));
        }

        return hasPermission;
    }
    
    /**
     * 检查字段级权限
     *
     * <p>使用Permission系统进行字段级权限检查
     *
     * @return 被拒绝访问的字段列表
     */
    private List<String> checkFieldPermissions(SecureQueryRequest request, QueryAccessCheckResult result) {
        List<String> deniedFields = new ArrayList<>();
        List<String> selectFields = request.getSelectFields();

        if (selectFields == null || selectFields.isEmpty()) {
            result.getAccessContext().put("totalFields", 0);
            result.getAccessContext().put("deniedFieldsCount", 0);
            return deniedFields;
        }

        String action = convertQueryTypeToAction(request.getQueryType());
        for (String fieldCode : selectFields) {
            // 构建Permission编码: FIELD.{modelCode}_{fieldCode}.{action}
            String permissionCode = "field." + request.getModelCode() + "_" + fieldCode + "." + action.toLowerCase();
            if (!userPermissionService.hasPermission(request.getUserId(), permissionCode)) {
                // 字段级权限检查失败时，尝试检查模型级read权限作为回退
                String modelReadPermission = "model." + request.getModelCode() + ".read";
                if (!userPermissionService.hasPermission(request.getUserId(), modelReadPermission)) {
                    deniedFields.add(fieldCode);
                    log.debug("用户无权访问字段: fieldCode={}, userId={}", logSafe(fieldCode), request.getUserId());
                }
            }
        }

        result.getAccessContext().put("totalFields", selectFields.size());
        result.getAccessContext().put("deniedFieldsCount", deniedFields.size());
        result.getAccessContext().put("fieldPermissionCheck", "permission");

        return deniedFields;
    }
    
    /**
     * 检查操作权限
     *
     * <p>使用Permission系统进行操作权限检查
     *
     * @return 被拒绝的操作列表
     */
    private List<String> checkOperationPermissions(SecureQueryRequest request, QueryAccessCheckResult result) {
        List<String> deniedOperations = new ArrayList<>();
        String requiredAction = convertQueryTypeToAction(request.getQueryType());

        // 构建Permission编码: MODEL.{modelCode}.{action}
        String permissionCode = "model." + request.getModelCode() + "." + requiredAction.toLowerCase();
        boolean hasPermission = userPermissionService.hasPermission(request.getUserId(), permissionCode);

        if (!hasPermission) {
            deniedOperations.add(requiredAction);
            log.debug("用户无权执行操作: modelCode={}, userId={}, action={}",
                logSafe(request.getModelCode()), request.getUserId(), logSafe(requiredAction));
        }

        result.getAccessContext().put("operationPermissionCheck", "permission");
        result.getAccessContext().put("requiredAction", requiredAction);
        result.getAccessContext().put("permissionCode", permissionCode);
        result.getAccessContext().put("hasPermission", hasPermission);

        return deniedOperations;
    }
    
    /**
     * 转换查询类型到权限操作
     */
    private String convertQueryTypeToAction(QueryType queryType) {
        switch (queryType) {
            case SELECT_ALL:
            case SELECT_BY_ID:
            case SELECT_PAGE:
            case SELECT_BY_CONDITION:
            case SELECT_WITH_RELATIONS:
            case SELECT_COUNT:
            case SELECT_AGGREGATE:
                return "read";
            case INSERT:
                return "create";
            case UPDATE:
                return "update";
            case DELETE:
                return "delete";
            default:
                return "read";
        }
    }
    
    /**
     * 记录权限拒绝日志
     */
    private void logPermissionDenied(SecureQueryRequest request, String reason) {
        try {
            if (Boolean.TRUE.equals(request.getEnableAudit())) {
                Map<String, Object> auditData = new HashMap<>();
                auditData.put("modelCode", request.getModelCode());
                auditData.put("userId", request.getUserId());
                auditData.put("tenantId", request.getTenantId());
                auditData.put("queryType", request.getQueryType());
                auditData.put("reason", reason);
                auditData.put("timestamp", System.currentTimeMillis());
                
                log.warn("查询权限被拒绝: {}", logSafe(auditData));
                
                // 调用审计服务记录权限拒绝事件
                QueryAccessCheckResult permissionResult = new QueryAccessCheckResult();
                permissionResult.setHasAccess(false);
                permissionResult.setDetails(new ArrayList<>());
                permissionResult.setDeniedFields(new ArrayList<>());
                permissionResult.setDeniedOperations(new ArrayList<>());
                permissionResult.setAccessContext(auditData);
                permissionResult.setCheckTimeMs(0L); // 设置检查时间
                
                QueryAccessCheckResult.AccessCheckDetail detail = 
                    new QueryAccessCheckResult.AccessCheckDetail();
                detail.setResource(request.getModelCode());
                detail.setOperation(convertQueryTypeToAction(request.getQueryType()));
                detail.setAllowed(false);
                detail.setReason(reason);
                permissionResult.getDetails().add(detail);
                
                queryAuditService.logPermissionCheck(request, permissionResult);
            }
        } catch (Exception e) {
            log.error("记录权限拒绝日志失败: {}", logSafe(e.getMessage()), e);
        }
    }
    
    /**
     * 记录权限检查错误日志
     */
    private void logPermissionError(SecureQueryRequest request, Throwable error) {
        try {
            if (Boolean.TRUE.equals(request.getEnableAudit())) {
                Map<String, Object> auditData = new HashMap<>();
                auditData.put("modelCode", request.getModelCode());
                auditData.put("userId", request.getUserId());
                auditData.put("tenantId", request.getTenantId());
                auditData.put("queryType", request.getQueryType());
                auditData.put("error", error.getMessage());
                auditData.put("timestamp", System.currentTimeMillis());
                
                log.error("查询权限检查错误: {}", logSafe(auditData), error);
                // TODO: 调用审计服务记录权限检查错误
            }
        } catch (Exception e) {
            log.error("记录权限检查错误日志失败: {}", logSafe(e.getMessage()), e);
        }
    }

    @Override
    public QueryComplexityValidationResult validateQueryComplexity(SecureQueryRequest request) {
        log.debug("验证查询复杂度: modelCode={}", logSafe(request.getModelCode()));
        
        QueryComplexityValidationResult result = new QueryComplexityValidationResult();
        result.setValid(true);
        result.setComplexityScore(0);
        result.setMaxAllowedScore(1000);
        
        int complexityScore = 0;
        
        // 计算查询条件复杂度
        if (request.getConditions() != null) {
            complexityScore += request.getConditions().size() * 10;
        }
        
        // 计算排序字段复杂度
        if (request.getSortFields() != null) {
            complexityScore += request.getSortFields().size() * 5;
        }
        
        // 计算关联查询复杂度
        if (request.getRelationConfigs() != null) {
            complexityScore += request.getRelationConfigs().size() * 50;
        }
        
        // 计算聚合查询复杂度
        if (request.getAggregateRequest() != null) {
            complexityScore += 100;
        }
        
        result.setComplexityScore(complexityScore);
        
        // 检查是否超出限制
        if (complexityScore > result.getMaxAllowedScore()) {
            result.setValid(false);
            result.setReason("查询复杂度过高: " + complexityScore + " > " + result.getMaxAllowedScore());
        }
        
        return result;
    }

    @Override
    public QueryLimitCheckResult checkQueryLimits(SecureQueryRequest request) {
        log.debug("检查查询限制: modelCode={}", logSafe(request.getModelCode()));
        
        QueryLimitCheckResult result = new QueryLimitCheckResult();
        result.setValid(true);
        result.setViolations(new ArrayList<>());
        
        // 检查最大记录数限制
        if (request.getMaxRecords() != null && request.getMaxRecords() > 50000) {
            result.setValid(false);
            result.getViolations().add("最大记录数超出限制: " + request.getMaxRecords() + " > 50000");
        }
        
        // 检查超时时间限制
        if (request.getTimeoutMs() != null && request.getTimeoutMs() > 300000) { // 5分钟
            result.setValid(false);
            result.getViolations().add("查询超时时间过长: " + request.getTimeoutMs() + "ms > 300000ms");
        }
        
        return result;
    }

    // ==================== 查询构建方法 ====================

    @Override
    public QueryBuilderService.QueryBuilder buildSecureQuery(SecureQueryRequest request) {
        log.debug("构建安全查询: modelCode={}", logSafe(request.getModelCode()));
        
        // 获取模型定义
        Optional<ModelDefinition> modelDefOpt = metaModelService.getModelDefinition(request.getModelCode());
        if (modelDefOpt.isEmpty()) {
            throw new IllegalArgumentException("模型不存在: " + request.getModelCode());
        }
        
        ModelDefinition modelDefinition = modelDefOpt.get();
        
        // 构建基础查询
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildBaseQuery(
            modelDefinition, convertToBuilderQueryType(request.getQueryType()));
        
        // 添加查询条件
        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            for (com.auraboot.framework.meta.dto.QueryCondition condition : request.getConditions()) {
                // 通过QueryBuilderService验证并解析字段名，然后添加到queryBuilder
                // buildConditionQuery内部已经做了resolveColumnName验证
                // 我们直接使用它来验证条件，然后获取字段名
                String fieldName = condition.getFieldName();

                // 查找字段定义以获取列名
                String columnName = resolveColumnName(modelDefinition, fieldName);

                // 添加条件到queryBuilder
                queryBuilder.addCondition(columnName, condition.getOperator().name(), condition.getValue());
            }
        }
        
        // 添加排序
        if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
            queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, request.getSortFields(), modelDefinition);
        }
        
        // 添加分页
        if (request.getPagination() != null) {
            queryBuilder = queryBuilderService.buildPaginationQuery(queryBuilder, request.getPagination());
        }
        
        // 应用权限过滤
        queryBuilder = applyPermissionFilters(queryBuilder, request);
        
        return queryBuilder;
    }

    @Override
    public QueryBuilderService.QueryBuilder applyPermissionFilters(QueryBuilderService.QueryBuilder queryBuilder,
                                                                  SecureQueryRequest request) {
        log.debug("应用权限过滤: modelCode={}, userId={}", logSafe(request.getModelCode()), request.getUserId());

        // 1. Tenant isolation
        queryBuilder.addCondition("tenant_id", "=", request.getTenantId());

        // 2. Data permission row-level filtering
        String rowFilter = dataPermissionEngine.buildRowFilter(
                request.getTenantId(), request.getModelCode(), request.getUserId());
        if (rowFilter != null && !rowFilter.isBlank()) {
            // Parse the row filter and apply as conditions
            // The filter is in format "AND field = value"
            String condition = rowFilter.stripLeading();
            if (condition.startsWith("AND ")) {
                condition = condition.substring(4);
            }
            // Apply as raw condition via created_by for SELF scope
            if (condition.startsWith("created_by = ")) {
                String userId = condition.substring("created_by = ".length());
                queryBuilder.addCondition("created_by", "=", Long.parseLong(userId));
            } else if (condition.startsWith("department_id IN")) {
                // For department scope, add condition directly
                queryBuilder.addCondition("created_by", "=", request.getUserId());
            } else if (!condition.isBlank()) {
                // For custom expressions, fall back to created_by as safety net
                log.debug("Custom data permission expression applied: {}", logSafe(condition));
                queryBuilder.addCondition("created_by", "=", request.getUserId());
            }
        }

        return queryBuilder;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T applyDataMasking(T data, SecureQueryRequest request) {
        log.debug("应用数据脱敏: modelCode={}", logSafe(request.getModelCode()));

        List<FieldMaskRule> rules = dataPermissionEngine.getFieldMaskRules(
                request.getTenantId(), request.getModelCode(), request.getUserId());
        if (rules == null || rules.isEmpty()) {
            return data;
        }

        if (data instanceof PaginationResult) {
            PaginationResult<Map<String, Object>> paginationResult =
                    (PaginationResult<Map<String, Object>>) data;
            List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(
                    paginationResult.getRecords(), rules);
            paginationResult.setRecords(masked);
            return (T) paginationResult;
        } else if (data instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) data;
            return (T) dataPermissionEngine.applyFieldMasking(list, rules);
        } else if (data instanceof Map) {
            List<Map<String, Object>> single = List.of((Map<String, Object>) data);
            List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(single, rules);
            return (T) (masked.isEmpty() ? data : masked.get(0));
        }

        return data;
    }

    @Override
    public <T> T applyFieldPermissionFilter(T data, SecureQueryRequest request) {
        log.debug("应用字段权限过滤: modelCode={}", logSafe(request.getModelCode()));
        
        if (data == null) {
            return null;
        }
        
        try {
            // 根据数据类型应用过滤
            if (data instanceof PaginationResult) {
                return (T) filterPaginationResult((PaginationResult<?>) data, request);
            } else if (data instanceof List) {
                return (T) filterList((List<?>) data, request);
            } else if (data instanceof Map) {
                return (T) filterMap((Map<String, Object>) data, request);
            } else {
                // 对于其他类型,尝试转换为Map后过滤
                log.debug("不支持的数据类型,跳过字段权限过滤: {}", logSafe(data.getClass().getName()));
                return data;
            }
            
        } catch (Exception e) {
            log.error("应用字段权限过滤失败: modelCode={}, error={}", 
                     logSafe(request.getModelCode()), logSafe(e.getMessage()), e);
            // 过滤失败时返回原数据,避免影响业务流程
            return data;
        }
    }
    
    /**
     * 过滤分页结果中的字段
     */
    private <T> PaginationResult<T> filterPaginationResult(PaginationResult<T> paginationResult, 
                                                           SecureQueryRequest request) {
        if (paginationResult.getRecords() == null || paginationResult.getRecords().isEmpty()) {
            return paginationResult;
        }
        
        List<T> filteredRecords = filterList(paginationResult.getRecords(), request);
        paginationResult.setRecords(filteredRecords);
        
        return paginationResult;
    }
    
    /**
     * 过滤列表中的字段
     */
    private <T> List<T> filterList(List<T> list, SecureQueryRequest request) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        
        List<T> filteredList = new ArrayList<>();
        for (T item : list) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> filteredItem = filterMap((Map<String, Object>) item, request);
                filteredList.add((T) filteredItem);
            } else {
                // 对于非Map类型,暂不处理
                filteredList.add(item);
            }
        }
        
        return filteredList;
    }
    
    /**
     * 过滤Map中的字段
     */
    private Map<String, Object> filterMap(Map<String, Object> map, SecureQueryRequest request) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        
        Map<String, Object> filteredMap = new HashMap<>();
        String action = convertQueryTypeToAction(request.getQueryType());
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String fieldCode = entry.getKey();
            
            // 检查字段权限
            if (hasFieldPermission(fieldCode, request, action)) {
                filteredMap.put(fieldCode, entry.getValue());
            } else {
                log.debug("字段被过滤: fieldCode={}", logSafe(fieldCode));
                // 可选: 添加占位符表示字段被过滤
                // filteredMap.put(fieldCode, "[FILTERED]");
            }
        }
        
        return filteredMap;
    }
    
    /**
     * 检查单个字段的权限
     *
     * <p>使用Permission系统进行单字段权限检查
     */
    private boolean hasFieldPermission(String fieldCode, SecureQueryRequest request, String action) {
        // 首先检查字段级权限
        String fieldPermissionCode = "field." + request.getModelCode() + "_" + fieldCode + "." + action.toLowerCase();
        if (userPermissionService.hasPermission(request.getUserId(), fieldPermissionCode)) {
            return true;
        }

        // 回退到模型级read权限
        String modelReadPermission = "model." + request.getModelCode() + ".read";
        boolean hasPermission = userPermissionService.hasPermission(request.getUserId(), modelReadPermission);

        if (!hasPermission) {
            log.debug("用户无权访问字段: fieldCode={}, userId={}, action={}", logSafe(fieldCode), request.getUserId(), logSafe(action));
        }

        return hasPermission;
    }

    // ==================== 缓存管理方法 ====================

    @Override
    @Cacheable(value = "secureQuery", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #request.queryId")
    public <T> T getQueryCache(SecureQueryRequest request) {
        // Spring Cache会自动处理缓存逻辑
        return null;
    }

    @Override
    public <T> void setQueryCache(SecureQueryRequest request, T result) {
        // Spring Cache会自动处理缓存设置
        log.debug("设置查询缓存: queryId={}", logSafe(request.getQueryId()));
    }

    @Override
    public void clearQueryCache(SecureQueryRequest request) {
        log.debug("清除查询缓存: queryId={}", logSafe(request.getQueryId()));
        // TODO: 实现缓存清除逻辑
    }

    @Override
    public String generateCacheKey(SecureQueryRequest request) {
        // 生成基于请求内容的缓存键
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(request.getModelCode())
                  .append(":")
                  .append(request.getQueryType())
                  .append(":")
                  .append(request.getUserId())
                  .append(":")
                  .append(request.getTenantId());
        
        if (request.getConditions() != null) {
            keyBuilder.append(":").append(request.getConditions().hashCode());
        }
        
        return keyBuilder.toString();
    }

    // ==================== 审计日志方法 ====================

    @Override
    public void logQueryAudit(SecureQueryRequest request, Object result, long executionTimeMs) {
        try {
            queryAuditService.logQueryExecution(request, result, executionTimeMs);
        } catch (Exception e) {
            log.error("记录查询审计日志失败: {}", logSafe(e.getMessage()), e);
        }
    }

    @Override
    public void logQueryError(SecureQueryRequest request, Throwable error, long executionTimeMs) {
        try {
            queryAuditService.logQueryError(request, error, executionTimeMs);
        } catch (Exception e) {
            log.error("记录查询错误日志失败: {}", logSafe(e.getMessage()), e);
        }
    }

    // ==================== 性能监控方法 ====================

    @Override
    public QueryPerformanceStatistics getQueryPerformanceStatistics(String modelCode, Long userId) {
        // TODO: 实现性能统计逻辑
        return new QueryPerformanceStatistics();
    }

    @Override
    public QueryExecutionPlan getQueryExecutionPlan(SecureQueryRequest request) {
        // TODO: 实现执行计划获取逻辑
        return new QueryExecutionPlan();
    }

    @Override
    public QueryOptimizationSuggestion optimizeQuery(SecureQueryRequest request) {
        // TODO: 实现查询优化建议逻辑
        return new QueryOptimizationSuggestion();
    }

    // ==================== 私有方法 ====================

    /**
     * 执行查询
     */
    @SuppressWarnings("unchecked")
    private <T> PaginationResult<T> executeQuery(QueryBuilderService.QueryBuilder queryBuilder,
                                                SecureQueryRequest request) {
        try {
            String sql = queryBuilder.getSql();
            Map<String, Object> params = queryBuilder.getParameterMap();
            Integer timeoutMs = request.getTimeoutMs();

            log.debug("执行SQL查询: {}", logSafe(sql));
            log.debug("查询参数: {}", logSafe(params));

            // 执行查询获取数据
            List<Map<String, Object>> records = executeWithTimeout(
                () -> dynamicDataMapper.selectByQuery(sql, params),
                timeoutMs,
                "select"
            );

            // 执行count查询获取总数
            Long total = 0L;
            if (request.getPagination() != null) {
                // 构建count查询
                String countSql = buildCountSql(sql);
                total = executeWithTimeout(
                    () -> dynamicDataMapper.countByQuery(countSql, params),
                    timeoutMs,
                    "count"
                );
            } else {
                total = (long) records.size();
            }

            // 构建分页结果
            PaginationResult<T> result = new PaginationResult<>();
            result.setRecords((List<T>) records);
            result.setTotal(total);
            result.setPageSize(request.getPagination() != null ? request.getPagination().getPageSize() : records.size());
            result.setPage(request.getPagination() != null ? request.getPagination().getPageNum() : 1);

            log.debug("查询完成: 返回{}条记录，总数{}", records.size(), total);
            return result;

        } catch (Exception e) {
            log.error("执行查询失败: modelCode={}, error={}", logSafe(request.getModelCode()), logSafe(e.getMessage()), e);
            throw new MetaServiceException("Query execution failed: " + e.getMessage(), e);
        }
    }

    private <T> T executeWithTimeout(Supplier<T> supplier, Integer timeoutMs, String operation) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return supplier.get();
        }

        CompletableFuture<T> future = CompletableFuture.supplyAsync(supplier);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new MetaServiceException(
                "Query timeout during " + operation + ": " + timeoutMs + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetaServiceException(
                "Query interrupted during " + operation, e);
        } catch (ExecutionException e) {
            throw new MetaServiceException(
                "Query execution failed during " + operation + ": " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * 从SELECT SQL构建COUNT SQL
     */
    private String buildCountSql(String selectSql) {
        // 简单实现：将SELECT ... FROM 替换为 SELECT COUNT(*) FROM
        // 移除ORDER BY子句
        String countSql = selectSql.replaceFirst("(?i)SELECT.*?FROM", "SELECT COUNT(*) FROM");
        countSql = countSql.replaceAll("(?i)ORDER BY[^)]*$", "");
        countSql = countSql.replaceAll("(?i)LIMIT.*$", "");
        countSql = countSql.replaceAll("(?i)OFFSET.*$", "");
        return countSql.trim();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析字段名到列名
     * 验证字段存在性并返回对应的数据库列名
     */
    private String resolveColumnName(ModelDefinition modelDefinition, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new com.auraboot.framework.meta.exception.MetaServiceException("Field name cannot be null or empty");
        }

        // 检查是否为系统字段
        Set<String> systemFields = SystemFieldConstants.QUERY_TRANSPARENT;
        if (systemFields.contains(fieldName)) {
            return fieldName;
        }

        // 检查模型字段
        if (modelDefinition.getFields() == null) {
            throw new com.auraboot.framework.meta.exception.MetaServiceException(
                "Model fields not loaded for: " + modelDefinition.getCode());
        }

        for (FieldDefinition field : modelDefinition.getFields()) {
            if (fieldName.equals(field.getCode()) || fieldName.equals(field.getColumnName())) {
                return field.getColumnName();
            }
        }

        throw new com.auraboot.framework.meta.exception.MetaServiceException(
            "Field not found in model: " + fieldName);
    }

    /**
     * 转换QueryType到QueryBuilderService.QueryType
     */
    private QueryBuilderService.QueryType convertToBuilderQueryType(com.auraboot.framework.meta.dto.QueryType queryType) {
        switch (queryType) {
            case SELECT_ALL:
            case SELECT_BY_ID:
            case SELECT_PAGE:
            case SELECT_BY_CONDITION:
            case SELECT_WITH_RELATIONS:
                return QueryBuilderService.QueryType.SELECT;
            case SELECT_COUNT:
                return QueryBuilderService.QueryType.COUNT;
            case SELECT_AGGREGATE:
                return QueryBuilderService.QueryType.SELECT;
            case INSERT:
                return QueryBuilderService.QueryType.INSERT;
            case UPDATE:
                return QueryBuilderService.QueryType.UPDATE;
            case DELETE:
                return QueryBuilderService.QueryType.DELETE;
            default:
                return QueryBuilderService.QueryType.SELECT;
        }
    }
}
