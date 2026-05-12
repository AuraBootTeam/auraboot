package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.QueryAuditLog;
import com.auraboot.framework.meta.mapper.QueryAuditLogMapper;
import com.auraboot.framework.meta.service.QueryAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Query audit service implementation.
 * Provides query execution logging, statistics, anomaly detection,
 * configuration management, data cleanup, and reporting.
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryAuditServiceImpl implements QueryAuditService {

    private final QueryAuditLogMapper queryAuditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * In-memory audit config per tenant. In production, this could be
     * backed by a DB table (ab_query_audit_config). For now we keep it
     * simple with defaults and a ConcurrentHashMap.
     */
    private final ConcurrentHashMap<Long, QueryAuditConfig> auditConfigCache = new ConcurrentHashMap<>();

    private static final int DEFAULT_SLOW_QUERY_THRESHOLD_MS = 5000;
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int MAX_EXPORT_BATCH_SIZE = 1000;
    private static final int MAX_ANOMALY_RESULTS = 100;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    // ==================== Audit Log Recording ====================

    @Override
    @Async("eventTaskExecutor")
    public void logQueryExecution(SecureQueryRequest request, Object result, long executionTimeMs) {
        try {
            log.debug("Recording query execution: modelCode={}, userId={}, executionTime={}ms",
                     logSafe(request.getModelCode()), request.getUserId(), executionTimeMs);

            QueryAuditLog auditLog = new QueryAuditLog();

            // Basic info
            auditLog.setTenantId(request.getTenantId());
            auditLog.setUserId(request.getUserId());
            auditLog.setQueryId(request.getQueryId());
            auditLog.setQueryCode(request.getModelCode());
            auditLog.setModelCode(request.getModelCode());
            auditLog.setQueryType(request.getQueryType().name());
            auditLog.setExecutionTimeMs((int) executionTimeMs);
            auditLog.setCostMs((int) executionTimeMs);
            auditLog.setSuccess(true);
            auditLog.setRejected(false);
            auditLog.setConditions("{}");
            auditLog.setCreatedAt(Instant.now());

            // Query conditions
            if (request.getConditions() != null && !request.getConditions().isEmpty()) {
                String condJson = toJson(request.getConditions());
                auditLog.setQueryConditions(condJson);
                if (condJson != null) {
                    auditLog.setConditions(condJson);
                }
            }

            // Select fields
            if (request.getSelectFields() != null && !request.getSelectFields().isEmpty()) {
                auditLog.setSelectFields(toJson(request.getSelectFields()));
            }

            // Sort fields
            if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
                auditLog.setSortFields(toJson(request.getSortFields()));
            }

            // Pagination info
            if (request.getPagination() != null) {
                auditLog.setPaginationInfo(toJson(request.getPagination()));
            }

            // Result info
            if (result instanceof PaginationResult) {
                PaginationResult<?> paginationResult = (PaginationResult<?>) result;
                auditLog.setResultCount(paginationResult.getTotal().intValue());
            } else if (result instanceof List) {
                List<?> listResult = (List<?>) result;
                auditLog.setResultCount(listResult.size());
            } else if (result != null) {
                auditLog.setResultCount(1);
            }

            // Cache & masking info
            auditLog.setCacheHit(Boolean.TRUE.equals(request.getEnableCache()));
            auditLog.setDataMaskingApplied(Boolean.TRUE.equals(request.getEnableDataMasking()));

            // Request info (IP, user-agent, etc.)
            setRequestInfo(auditLog);

            queryAuditLogMapper.insert(auditLog);
            log.debug("Query execution log saved: id={}", auditLog.getId());

        } catch (Exception e) {
            log.error("Failed to record query execution: modelCode={}, userId={}, error={}",
                     logSafe(request.getModelCode()), request.getUserId(), logSafe(e.getMessage()), e);
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void logQueryError(SecureQueryRequest request, Throwable error, long executionTimeMs) {
        try {
            log.debug("Recording query error: modelCode={}, userId={}, error={}",
                     logSafe(request.getModelCode()), request.getUserId(), logSafe(error.getMessage()));

            QueryAuditLog auditLog = new QueryAuditLog();

            auditLog.setTenantId(request.getTenantId());
            auditLog.setUserId(request.getUserId());
            auditLog.setQueryId(request.getQueryId());
            auditLog.setQueryCode(request.getModelCode());
            auditLog.setModelCode(request.getModelCode());
            auditLog.setQueryType(request.getQueryType().name());
            auditLog.setExecutionTimeMs((int) executionTimeMs);
            auditLog.setCostMs((int) executionTimeMs);
            auditLog.setSuccess(false);
            auditLog.setRejected(false);
            auditLog.setConditions("{}");
            auditLog.setCreatedAt(Instant.now());

            auditLog.setErrorMessage(truncate(error.getMessage(), 2000));
            auditLog.setErrorType(error.getClass().getSimpleName());

            if (request.getConditions() != null && !request.getConditions().isEmpty()) {
                String condJson = toJson(request.getConditions());
                auditLog.setQueryConditions(condJson);
                if (condJson != null) {
                    auditLog.setConditions(condJson);
                }
            }

            setRequestInfo(auditLog);
            queryAuditLogMapper.insert(auditLog);
            log.debug("Query error log saved: id={}", auditLog.getId());

        } catch (Exception e) {
            log.error("Failed to record query error: modelCode={}, userId={}, error={}",
                     logSafe(request.getModelCode()), request.getUserId(), logSafe(e.getMessage()), e);
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void logPermissionCheck(SecureQueryRequest request, QueryAccessCheckResult permissionResult) {
        try {
            log.debug("Recording permission check: modelCode={}, userId={}, hasAccess={}",
                     logSafe(request.getModelCode()), request.getUserId(), permissionResult.getHasPermission());

            // Record permission denial as a security audit event
            if (!permissionResult.getHasPermission()) {
                QueryAuditLog auditLog = new QueryAuditLog();
                auditLog.setTenantId(request.getTenantId());
                auditLog.setUserId(request.getUserId());
                auditLog.setQueryCode(request.getModelCode());
                auditLog.setModelCode(request.getModelCode());
                auditLog.setQueryType("permission_check");
                auditLog.setSuccess(false);
                auditLog.setRejected(true);
                auditLog.setRejectReason("Permission denied: " + permissionResult.getDenyReason());
                auditLog.setConditions("{}");
                auditLog.setCreatedAt(Instant.now());
                auditLog.setErrorType("PermissionDenied");
                auditLog.setErrorMessage("User " + request.getUserId()
                        + " denied access to model " + request.getModelCode());

                setRequestInfo(auditLog);
                queryAuditLogMapper.insert(auditLog);

                log.warn("Permission denial recorded: userId={}, model={}",
                        request.getUserId(), logSafe(request.getModelCode()));
            }

        } catch (Exception e) {
            log.error("Failed to record permission check: modelCode={}, userId={}, error={}",
                     logSafe(request.getModelCode()), request.getUserId(), logSafe(e.getMessage()), e);
        }
    }

    @Override
    @Async("eventTaskExecutor")
    public void logSecurityValidation(SecureQueryRequest request, QuerySecurityValidationResult securityResult) {
        try {
            log.debug("Recording security validation: modelCode={}, userId={}, valid={}",
                     logSafe(request.getModelCode()), request.getUserId(), securityResult.getValid());

            // Record security validation failures
            if (!securityResult.getValid()
                    || (securityResult.getSecurityIssues() != null && !securityResult.getSecurityIssues().isEmpty())) {

                QueryAuditLog auditLog = new QueryAuditLog();
                auditLog.setTenantId(request.getTenantId());
                auditLog.setUserId(request.getUserId());
                auditLog.setQueryCode(request.getModelCode());
                auditLog.setModelCode(request.getModelCode());
                auditLog.setQueryType("security_validation");
                auditLog.setSuccess(false);
                auditLog.setRejected(!securityResult.getValid());
                auditLog.setConditions("{}");
                auditLog.setCreatedAt(Instant.now());

                // Build issue description from SecurityIssue objects
                String issues;
                if (securityResult.getSecurityIssues() != null && !securityResult.getSecurityIssues().isEmpty()) {
                    issues = securityResult.getSecurityIssues().stream()
                            .map(issue -> issue.getType() + ": " + issue.getDescription())
                            .collect(Collectors.joining("; "));
                } else {
                    issues = "Security validation failed";
                }
                auditLog.setRejectReason(truncate(issues, 2000));
                auditLog.setErrorType("SecurityValidationFailure");
                auditLog.setErrorMessage(truncate(issues, 2000));

                if (request.getConditions() != null && !request.getConditions().isEmpty()) {
                    String condJson = toJson(request.getConditions());
                    auditLog.setQueryConditions(condJson);
                    if (condJson != null) {
                        auditLog.setConditions(condJson);
                    }
                }

                setRequestInfo(auditLog);
                queryAuditLogMapper.insert(auditLog);

                log.warn("Security validation failure recorded: userId={}, model={}, issues={}",
                        request.getUserId(), logSafe(request.getModelCode()), logSafe(issues));
            }

        } catch (Exception e) {
            log.error("Failed to record security validation: modelCode={}, userId={}, error={}",
                     logSafe(request.getModelCode()), request.getUserId(), logSafe(e.getMessage()), e);
        }
    }

    // ==================== Audit Log Querying ====================

    @Override
    public PageResult<QueryAuditLogDTO> queryAuditLogs(QueryAuditLogQueryRequest request) {
        log.debug("Querying audit logs: tenantId={}, page={}, size={}",
                 request.getTenantId(), request.getPage(), request.getSize());

        Page<QueryAuditLog> pageRequest = new Page<>(request.getPage(), request.getSize());
        IPage<QueryAuditLog> pageResult = queryAuditLogMapper.selectPageList(
            pageRequest,
            request.getTenantId(),
            request.getUserId(),
            request.getModelCode(),
            request.getQueryType(),
            request.getSuccess(),
            DateUtil.toUtcInstant(request.getStartTime()),
            DateUtil.toUtcInstant(request.getEndTime())
        );

        List<QueryAuditLogDTO> dtoList = pageResult.getRecords().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        return new PageResult<>(
            dtoList,
            pageResult.getTotal(),
            Long.valueOf(request.getSize()),
            Long.valueOf(request.getPage())
        );
    }

    @Override
    public List<QueryAuditLogDTO> queryAuditLogsByUser(Long userId, Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Querying audit logs by user: userId={}, tenantId={}", userId, tenantId);
        List<QueryAuditLog> logs = queryAuditLogMapper.findByUserId(tenantId, userId, startTime, endTime);
        return logs.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<QueryAuditLogDTO> queryAuditLogsByModel(String modelCode, Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Querying audit logs by model: modelCode={}, tenantId={}", logSafe(modelCode), tenantId);
        List<QueryAuditLog> logs = queryAuditLogMapper.findByModelCode(tenantId, modelCode, startTime, endTime);
        return logs.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<QueryAuditLogDTO> queryFailedQueries(Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Querying failed queries: tenantId={}", tenantId);
        List<QueryAuditLog> logs = queryAuditLogMapper.findFailedQueries(tenantId, startTime, endTime);
        return logs.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    // ==================== Statistics & Analytics ====================

    @Override
    public QueryAuditStatistics getQueryStatistics(QueryAuditStatisticsRequest request) {
        log.debug("Generating query statistics: tenantId={}", request.getTenantId());

        Long tenantId = request.getTenantId();
        Instant startTime = resolveStartTime(request.getStartTime());
        Instant endTime = resolveEndTime(request.getEndTime());
        int slowThreshold = request.getSlowQueryThreshold() != null
                ? request.getSlowQueryThreshold() : DEFAULT_SLOW_QUERY_THRESHOLD_MS;

        QueryAuditStatistics stats = new QueryAuditStatistics();
        stats.setTenantId(tenantId);
        stats.setStartTime(DateUtil.toUtcLocalDateTime(startTime));
        stats.setEndTime(DateUtil.toUtcLocalDateTime(endTime));

        // Core counts
        Long totalQueries = queryAuditLogMapper.countByTenantAndTimeRange(tenantId, startTime, endTime);
        Long successfulQueries = queryAuditLogMapper.countSuccessfulQueries(tenantId, startTime, endTime);
        Long failedQueries = queryAuditLogMapper.countFailedQueries(tenantId, startTime, endTime);

        stats.setTotalQueries(nullSafe(totalQueries));
        stats.setSuccessfulQueries(nullSafe(successfulQueries));
        stats.setFailedQueries(nullSafe(failedQueries));
        stats.setSuccessRate(safePercentage(stats.getSuccessfulQueries(), stats.getTotalQueries()));

        // Execution time stats
        stats.setAverageExecutionTime(nullToZero(
                queryAuditLogMapper.calculateAverageExecutionTime(tenantId, startTime, endTime)));
        stats.setMaxExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getMaxExecutionTime(tenantId, startTime, endTime)));
        stats.setMinExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getMinExecutionTime(tenantId, startTime, endTime)));

        // Slow queries
        stats.setSlowQueryCount(nullSafe(
                queryAuditLogMapper.countSlowQueries(tenantId, slowThreshold, startTime, endTime)));
        stats.setSlowQueryThreshold(slowThreshold);

        // Cache stats
        stats.setCacheHitCount(nullSafe(
                queryAuditLogMapper.countCacheHits(tenantId, startTime, endTime)));
        stats.setCacheHitRate(safePercentage(stats.getCacheHitCount(), stats.getTotalQueries()));

        // Data masking & security
        stats.setDataMaskingCount(nullSafe(
                queryAuditLogMapper.countDataMaskingApplications(tenantId, startTime, endTime)));

        // Unique counts
        stats.setUniqueUserCount(nullSafe(
                queryAuditLogMapper.countUniqueUsers(tenantId, startTime, endTime)));
        stats.setUniqueModelCount(nullSafe(
                queryAuditLogMapper.countUniqueModels(tenantId, startTime, endTime)));
        stats.setTotalRecordsReturned(nullSafe(
                queryAuditLogMapper.sumTotalRecordsReturned(tenantId, startTime, endTime)));

        // Group-by statistics
        stats.setQueryTypeStatistics(mapToStringLong(
                queryAuditLogMapper.countByQueryType(tenantId, startTime, endTime), "query_type"));
        stats.setModelStatistics(mapToStringLong(
                queryAuditLogMapper.countByModel(tenantId, startTime, endTime), "model_code"));
        stats.setUserStatistics(mapToLongLong(
                queryAuditLogMapper.countByUser(tenantId, startTime, endTime), "user_id"));
        stats.setErrorTypeStatistics(mapToStringLong(
                queryAuditLogMapper.countByErrorType(tenantId, startTime, endTime), "error_type"));

        // Security violations derived from error types
        long secViolations = 0;
        if (stats.getErrorTypeStatistics() != null) {
            secViolations = stats.getErrorTypeStatistics().entrySet().stream()
                    .filter(e -> "PermissionDenied".equals(e.getKey())
                            || "SecurityValidationFailure".equals(e.getKey()))
                    .mapToLong(Map.Entry::getValue)
                    .sum();
        }
        stats.setSecurityViolationCount(secViolations);

        // Optional: hourly stats
        if (Boolean.TRUE.equals(request.getIncludeHourlyStats())) {
            stats.setHourlyStatistics(mapToIntLong(
                    queryAuditLogMapper.countByHour(tenantId, startTime, endTime), "hour"));
        }

        // Optional: daily stats
        if (Boolean.TRUE.equals(request.getIncludeDailyStats())) {
            stats.setDailyStatistics(mapToStringLong(
                    queryAuditLogMapper.countByDate(tenantId, startTime, endTime), "date"));
        }

        // Execution time distribution
        stats.setExecutionTimeDistribution(mapToStringLong(
                queryAuditLogMapper.getExecutionTimeDistribution(tenantId, startTime, endTime), "bucket"));

        log.debug("Query statistics generated: total={}, successRate={}%",
                stats.getTotalQueries(), stats.getSuccessRate());
        return stats;
    }

    @Override
    public UserQueryStatistics getUserQueryStatistics(Long userId, Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Generating user query statistics: userId={}, tenantId={}", userId, tenantId);

        Instant start = startTime != null ? startTime : Instant.now().minus(30, ChronoUnit.DAYS);
        Instant end = endTime != null ? endTime : Instant.now();

        UserQueryStatistics stats = new UserQueryStatistics();
        stats.setUserId(userId);
        stats.setTenantId(tenantId);
        stats.setStartTime(DateUtil.toUtcLocalDateTime(start));
        stats.setEndTime(DateUtil.toUtcLocalDateTime(end));

        // Core counts
        stats.setTotalQueries(nullSafe(
                queryAuditLogMapper.countByTenantUserAndTimeRange(tenantId, userId, start, end)));
        stats.setSuccessfulQueries(nullSafe(
                queryAuditLogMapper.countUserSuccessfulQueries(tenantId, userId, start, end)));
        stats.setFailedQueries(nullSafe(
                queryAuditLogMapper.countUserFailedQueries(tenantId, userId, start, end)));
        stats.setSuccessRate(safePercentage(stats.getSuccessfulQueries(), stats.getTotalQueries()));

        // Execution time
        stats.setAverageExecutionTime(nullToZero(
                queryAuditLogMapper.calculateUserAverageExecutionTime(tenantId, userId, start, end)));
        stats.setMaxExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getUserMaxExecutionTime(tenantId, userId, start, end)));
        stats.setMinExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getUserMinExecutionTime(tenantId, userId, start, end)));

        // Slow queries (count for this user within the time range)
        // We reuse the tenant-wide slow query count with user filter via countByConditions
        stats.setSlowQueryCount(0L); // basic default; a dedicated user slow query count could be added

        // Cache & masking (tenant-wide as user-level cache tracking is not isolated)
        stats.setCacheHitCount(nullSafe(
                queryAuditLogMapper.countCacheHits(tenantId, start, end)));
        stats.setCacheHitRate(safePercentage(stats.getCacheHitCount(), stats.getTotalQueries()));
        stats.setDataMaskingCount(nullSafe(
                queryAuditLogMapper.countDataMaskingApplications(tenantId, start, end)));

        // Model access
        stats.setAccessedModelCount(nullSafe(
                queryAuditLogMapper.countUserAccessedModels(tenantId, userId, start, end)));
        stats.setTotalRecordsReturned(nullSafe(
                queryAuditLogMapper.sumTotalRecordsReturned(tenantId, start, end)));

        // Last query time
        Instant lastQuery = queryAuditLogMapper.getUserLastQueryTime(tenantId, userId, start, end);
        stats.setLastQueryTime(lastQuery != null ? DateUtil.toUtcLocalDateTime(lastQuery) : null);

        // Most active hour
        stats.setMostActiveHour(queryAuditLogMapper.getUserMostActiveHour(tenantId, userId, start, end));

        // Group-by statistics
        stats.setQueryTypeStatistics(mapToStringLong(
                queryAuditLogMapper.countUserByQueryType(tenantId, userId, start, end), "query_type"));
        stats.setModelStatistics(mapToStringLong(
                queryAuditLogMapper.countUserByModel(tenantId, userId, start, end), "model_code"));

        // Recent queries (top 10)
        List<QueryAuditLog> recentLogs = queryAuditLogMapper.findByUserId(tenantId, userId, start, end);
        if (recentLogs != null && !recentLogs.isEmpty()) {
            stats.setRecentQueries(recentLogs.stream()
                    .limit(10)
                    .map(this::convertToDTO)
                    .collect(Collectors.toList()));
        }

        log.debug("User statistics generated: userId={}, total={}", userId, stats.getTotalQueries());
        return stats;
    }

    @Override
    public ModelQueryStatistics getModelQueryStatistics(String modelCode, Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Generating model query statistics: modelCode={}, tenantId={}", logSafe(modelCode), tenantId);

        Instant start = startTime != null ? startTime : Instant.now().minus(30, ChronoUnit.DAYS);
        Instant end = endTime != null ? endTime : Instant.now();

        ModelQueryStatistics stats = new ModelQueryStatistics();
        stats.setModelCode(modelCode);
        stats.setTenantId(tenantId);
        stats.setStartTime(DateUtil.toUtcLocalDateTime(start));
        stats.setEndTime(DateUtil.toUtcLocalDateTime(end));

        // Core counts
        stats.setTotalQueries(nullSafe(
                queryAuditLogMapper.countByTenantModelAndTimeRange(tenantId, modelCode, start, end)));
        stats.setSuccessfulQueries(nullSafe(
                queryAuditLogMapper.countModelSuccessfulQueries(tenantId, modelCode, start, end)));
        stats.setFailedQueries(nullSafe(
                queryAuditLogMapper.countModelFailedQueries(tenantId, modelCode, start, end)));
        stats.setSuccessRate(safePercentage(stats.getSuccessfulQueries(), stats.getTotalQueries()));

        // Execution time
        stats.setAverageExecutionTime(nullToZero(
                queryAuditLogMapper.calculateModelAverageExecutionTime(tenantId, modelCode, start, end)));
        stats.setMaxExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getModelMaxExecutionTime(tenantId, modelCode, start, end)));
        stats.setMinExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getModelMinExecutionTime(tenantId, modelCode, start, end)));

        // Slow queries
        stats.setSlowQueryCount(nullSafe(
                queryAuditLogMapper.countSlowQueries(tenantId, DEFAULT_SLOW_QUERY_THRESHOLD_MS, start, end)));

        // Cache & masking
        stats.setCacheHitCount(nullSafe(
                queryAuditLogMapper.countCacheHits(tenantId, start, end)));
        stats.setCacheHitRate(safePercentage(stats.getCacheHitCount(), stats.getTotalQueries()));
        stats.setDataMaskingCount(nullSafe(
                queryAuditLogMapper.countDataMaskingApplications(tenantId, start, end)));

        // User access
        stats.setAccessUserCount(nullSafe(
                queryAuditLogMapper.countModelAccessUsers(tenantId, modelCode, start, end)));
        stats.setTotalRecordsReturned(nullSafe(
                queryAuditLogMapper.sumTotalRecordsReturned(tenantId, start, end)));

        // Most active hour
        stats.setMostActiveHour(queryAuditLogMapper.getModelMostActiveHour(tenantId, modelCode, start, end));

        // Recent queries (top 10)
        List<QueryAuditLog> recentLogs = queryAuditLogMapper.findByModelCode(tenantId, modelCode, start, end);
        if (recentLogs != null && !recentLogs.isEmpty()) {
            stats.setRecentQueries(recentLogs.stream()
                    .limit(10)
                    .map(this::convertToDTO)
                    .collect(Collectors.toList()));
        }

        log.debug("Model statistics generated: modelCode={}, total={}", logSafe(modelCode), stats.getTotalQueries());
        return stats;
    }

    @Override
    public QueryPerformanceStatistics getQueryPerformanceStatistics(Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Generating performance statistics: tenantId={}", tenantId);

        Instant start = startTime != null ? startTime : Instant.now().minus(7, ChronoUnit.DAYS);
        Instant end = endTime != null ? endTime : Instant.now();

        QueryPerformanceStatistics stats = new QueryPerformanceStatistics();
        stats.setTenantId(tenantId);
        stats.setStartTime(DateUtil.toUtcLocalDateTime(start));
        stats.setEndTime(DateUtil.toUtcLocalDateTime(end));

        // Total queries
        Long totalQueries = queryAuditLogMapper.countByTenantAndTimeRange(tenantId, start, end);
        stats.setTotalQueries(nullSafe(totalQueries));

        // Execution time aggregates
        stats.setAverageExecutionTime(nullToZero(
                queryAuditLogMapper.calculateAverageExecutionTime(tenantId, start, end)));
        stats.setMaxExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getMaxExecutionTime(tenantId, start, end)));
        stats.setMinExecutionTime(nullToZeroInt(
                queryAuditLogMapper.getMinExecutionTime(tenantId, start, end)));

        // Percentiles via PostgreSQL PERCENTILE_CONT
        stats.setMedianExecutionTime(nullToZero(
                queryAuditLogMapper.calculatePercentileExecutionTime(tenantId, 0.5, start, end)));
        stats.setP95ExecutionTime(nullToZero(
                queryAuditLogMapper.calculatePercentileExecutionTime(tenantId, 0.95, start, end)));
        stats.setP99ExecutionTime(nullToZero(
                queryAuditLogMapper.calculatePercentileExecutionTime(tenantId, 0.99, start, end)));

        // Slow queries
        int slowThreshold = DEFAULT_SLOW_QUERY_THRESHOLD_MS;
        stats.setSlowQueryCount(nullSafe(
                queryAuditLogMapper.countSlowQueries(tenantId, slowThreshold, start, end)));
        stats.setSlowQueryThreshold(slowThreshold);
        stats.setSlowQueryRate(safePercentage(stats.getSlowQueryCount(), stats.getTotalQueries()));

        // Timeout queries (>30s)
        stats.setTimeoutQueryCount(nullSafe(
                queryAuditLogMapper.countSlowQueries(tenantId, 30000, start, end)));

        // Cache stats
        stats.setCacheHitCount(nullSafe(
                queryAuditLogMapper.countCacheHits(tenantId, start, end)));
        long cacheMiss = stats.getTotalQueries() - stats.getCacheHitCount();
        stats.setCacheMissCount(Math.max(cacheMiss, 0L));
        stats.setCacheHitRate(safePercentage(stats.getCacheHitCount(), stats.getTotalQueries()));

        // Permission & security timing
        stats.setAveragePermissionCheckTime(nullToZero(
                queryAuditLogMapper.calculateAveragePermissionCheckTime(tenantId, start, end)));
        stats.setAverageSecurityValidationTime(nullToZero(
                queryAuditLogMapper.calculateAverageSecurityValidationTime(tenantId, start, end)));

        // Data masking
        stats.setDataMaskingCount(nullSafe(
                queryAuditLogMapper.countDataMaskingApplications(tenantId, start, end)));

        // QPS (queries per second)
        long durationSeconds = ChronoUnit.SECONDS.between(start, end);
        stats.setQueryThroughput(durationSeconds > 0
                ? (double) stats.getTotalQueries() / durationSeconds : 0.0);

        // Error rate
        Long failedCount = queryAuditLogMapper.countFailedQueries(tenantId, start, end);
        stats.setErrorRate(safePercentage(nullSafe(failedCount), stats.getTotalQueries()));

        // Execution time distribution
        stats.setExecutionTimeDistribution(mapToStringLong(
                queryAuditLogMapper.getExecutionTimeDistribution(tenantId, start, end), "bucket"));

        // Performance trend (hourly)
        List<Map<String, Object>> trendData = queryAuditLogMapper.getPerformanceTrendByHour(tenantId, start, end);
        if (trendData != null && !trendData.isEmpty()) {
            stats.setPerformanceTrend(trendData.stream().map(row -> {
                QueryPerformanceStatistics.PerformanceTrendPoint point =
                        new QueryPerformanceStatistics.PerformanceTrendPoint();
                Object ts = row.get("ts");
                if (ts instanceof java.sql.Timestamp) {
                    point.setTimestamp(((java.sql.Timestamp) ts).toLocalDateTime());
                } else if (ts instanceof java.time.OffsetDateTime) {
                    point.setTimestamp(((java.time.OffsetDateTime) ts).toLocalDateTime());
                } else if (ts instanceof Instant) {
                    point.setTimestamp(DateUtil.toUtcLocalDateTime((Instant) ts));
                }
                point.setQueryCount(toLong(row.get("query_count")));
                point.setAverageExecutionTime(toDouble(row.get("avg_time")));
                point.setP95ExecutionTime(toDouble(row.get("p95_time")));
                point.setCacheHitRate(toDouble(row.get("cache_hit_rate")));
                point.setErrorRate(toDouble(row.get("error_rate")));
                return point;
            }).collect(Collectors.toList()));
        }

        // Slow query details (top 10)
        List<QueryAuditLog> slowLogs = queryAuditLogMapper.getSlowQueries(
                tenantId, slowThreshold, start, end, 10);
        if (slowLogs != null && !slowLogs.isEmpty()) {
            stats.setSlowQueries(slowLogs.stream().map(entry -> {
                QueryPerformanceStatistics.SlowQueryDetail detail =
                        new QueryPerformanceStatistics.SlowQueryDetail();
                detail.setQueryId(entry.getQueryId());
                detail.setModelCode(entry.getModelCode());
                detail.setUserId(entry.getUserId());
                detail.setExecutionTime(entry.getExecutionTimeMs() != null
                        ? entry.getExecutionTimeMs() : entry.getCostMs());
                detail.setQueryConditions(entry.getQueryConditions());
                detail.setCreatedAt(DateUtil.toUtcLocalDateTime(entry.getCreatedAt()));
                return detail;
            }).collect(Collectors.toList()));
        }

        log.debug("Performance statistics generated: total={}, avg={}ms, p95={}ms",
                stats.getTotalQueries(), stats.getAverageExecutionTime(), stats.getP95ExecutionTime());
        return stats;
    }

    // ==================== Anomaly Detection ====================

    @Override
    public QueryAnomalyDetectionResult detectAnomalousQueries(QueryAnomalyDetectionRequest request) {
        log.debug("Detecting anomalous queries: tenantId={}", request.getTenantId());

        Long tenantId = request.getTenantId();
        Instant startTime = resolveStartTime(request.getStartTime());
        Instant endTime = resolveEndTime(request.getEndTime());
        int maxAnomalies = request.getMaxAnomalies() != null ? request.getMaxAnomalies() : MAX_ANOMALY_RESULTS;

        QueryAnomalyDetectionResult result = new QueryAnomalyDetectionResult();
        result.setTenantId(tenantId);
        result.setStartTime(DateUtil.toUtcLocalDateTime(startTime));
        result.setEndTime(DateUtil.toUtcLocalDateTime(endTime));

        List<QueryAnomalyDetectionResult.QueryAnomaly> anomalies = new ArrayList<>();
        Instant detectionStart = Instant.now();

        // 1. High-frequency users
        int freqThreshold = request.getFrequentQueryThreshold() != null
                ? request.getFrequentQueryThreshold() : 100;
        List<Map<String, Object>> highFreqUsers = queryAuditLogMapper.findHighFrequencyUsers(
                tenantId, startTime, endTime, freqThreshold);
        if (highFreqUsers != null) {
            for (Map<String, Object> row : highFreqUsers) {
                if (anomalies.size() >= maxAnomalies) break;
                QueryAnomalyDetectionResult.QueryAnomaly a = new QueryAnomalyDetectionResult.QueryAnomaly();
                a.setAnomalyId(UUID.randomUUID().toString());
                a.setAnomalyType("high_frequency");
                a.setSeverity("medium");
                a.setRiskScore(60);
                a.setUserId(toLong(row.get("user_id")));
                a.setDescription("User executed " + row.get("query_count")
                        + " queries (threshold: " + freqThreshold + ")");
                a.setDetectedAt(LocalDateTime.now(ZoneOffset.UTC));
                a.setHandled(false);
                a.setRecommendedAction("Review user query patterns; consider rate limiting");
                anomalies.add(a);
            }
        }

        // 2. Abnormally slow queries
        Double multiplier = request.getAbnormalExecutionTimeMultiplier() != null
                ? request.getAbnormalExecutionTimeMultiplier() : 5.0;
        int remaining = Math.max(maxAnomalies - anomalies.size(), 0);
        if (remaining > 0) {
            List<QueryAuditLog> abnormallySlow = queryAuditLogMapper.findAbnormallySlowQueries(
                    tenantId, startTime, endTime, multiplier, Math.min(remaining, 20));
            if (abnormallySlow != null) {
                for (QueryAuditLog entry : abnormallySlow) {
                    if (anomalies.size() >= maxAnomalies) break;
                    QueryAnomalyDetectionResult.QueryAnomaly a = new QueryAnomalyDetectionResult.QueryAnomaly();
                    a.setAnomalyId(UUID.randomUUID().toString());
                    a.setAnomalyType("abnormal_execution_time");
                    a.setSeverity("high");
                    a.setRiskScore(75);
                    a.setUserId(entry.getUserId());
                    a.setModelCode(entry.getModelCode());
                    a.setQueryId(entry.getQueryId());
                    int execTime = entry.getExecutionTimeMs() != null ? entry.getExecutionTimeMs()
                            : (entry.getCostMs() != null ? entry.getCostMs() : 0);
                    a.setDescription("Execution time " + execTime + "ms exceeds " + multiplier + "x average");
                    a.setDetectedAt(LocalDateTime.now(ZoneOffset.UTC));
                    a.setQueryConditions(entry.getQueryConditions());
                    a.setHandled(false);
                    a.setRecommendedAction("Optimize query; check for missing indexes");
                    anomalies.add(a);
                }
            }
        }

        // 3. Off-hours activity
        if (Boolean.TRUE.equals(request.getDetectAbnormalAccess())) {
            remaining = Math.max(maxAnomalies - anomalies.size(), 0);
            if (remaining > 0) {
                List<Map<String, Object>> offHours = queryAuditLogMapper.findOffHoursQueries(
                        tenantId, startTime, endTime, 3);
                if (offHours != null) {
                    for (Map<String, Object> row : offHours) {
                        if (anomalies.size() >= maxAnomalies) break;
                        QueryAnomalyDetectionResult.QueryAnomaly a = new QueryAnomalyDetectionResult.QueryAnomaly();
                        a.setAnomalyId(UUID.randomUUID().toString());
                        a.setAnomalyType("off_hours_access");
                        a.setSeverity("low");
                        a.setRiskScore(40);
                        a.setUserId(toLong(row.get("user_id")));
                        a.setDescription("User performed " + row.get("count")
                                + " queries at hour " + row.get("hour") + " (off-hours)");
                        a.setDetectedAt(LocalDateTime.now(ZoneOffset.UTC));
                        a.setHandled(false);
                        a.setRecommendedAction("Verify if off-hours access is authorized");
                        anomalies.add(a);
                    }
                }
            }
        }

        // 4. Large result set queries (potential data exfiltration)
        if (Boolean.TRUE.equals(request.getDetectDataLeakage())) {
            remaining = Math.max(maxAnomalies - anomalies.size(), 0);
            if (remaining > 0) {
                List<QueryAuditLog> largeResults = queryAuditLogMapper.findLargeResultSetQueries(
                        tenantId, startTime, endTime, 10.0, Math.min(remaining, 10));
                if (largeResults != null) {
                    for (QueryAuditLog entry : largeResults) {
                        if (anomalies.size() >= maxAnomalies) break;
                        QueryAnomalyDetectionResult.QueryAnomaly a = new QueryAnomalyDetectionResult.QueryAnomaly();
                        a.setAnomalyId(UUID.randomUUID().toString());
                        a.setAnomalyType("large_result_set");
                        a.setSeverity("medium");
                        a.setRiskScore(55);
                        a.setUserId(entry.getUserId());
                        a.setModelCode(entry.getModelCode());
                        a.setQueryId(entry.getQueryId());
                        a.setDescription("Query returned " + entry.getResultCount()
                                + " records (significantly above average)");
                        a.setDetectedAt(LocalDateTime.now(ZoneOffset.UTC));
                        a.setHandled(false);
                        a.setRecommendedAction("Review query; ensure appropriate row limits");
                        anomalies.add(a);
                    }
                }
            }
        }

        // Build result
        result.setAnomalies(anomalies);
        result.setAnomaliesDetected(!anomalies.isEmpty());
        result.setTotalAnomalies(anomalies.size());

        int high = 0, medium = 0, low = 0;
        for (QueryAnomalyDetectionResult.QueryAnomaly a : anomalies) {
            switch (a.getSeverity()) {
                case "high": high++; break;
                case "medium": medium++; break;
                case "low": low++; break;
            }
        }
        result.setHighRiskAnomalies(high);
        result.setMediumRiskAnomalies(medium);
        result.setLowRiskAnomalies(low);

        // Execution info
        QueryAnomalyDetectionResult.DetectionExecutionInfo execInfo =
                new QueryAnomalyDetectionResult.DetectionExecutionInfo();
        execInfo.setExecutionStartTime(DateUtil.toUtcLocalDateTime(detectionStart));
        execInfo.setExecutionEndTime(LocalDateTime.now(ZoneOffset.UTC));
        execInfo.setExecutionDurationMs(java.time.Duration.between(detectionStart, Instant.now()).toMillis());
        execInfo.setDetectionVersion("1.0");
        execInfo.setAppliedRules(List.of("high_frequency", "abnormal_execution_time",
                "off_hours_access", "large_result_set"));
        result.setExecutionInfo(execInfo);

        // Risk assessment
        QueryAnomalyDetectionResult.RiskAssessment risk = new QueryAnomalyDetectionResult.RiskAssessment();
        int maxScore = anomalies.stream()
                .mapToInt(a -> a.getRiskScore() != null ? a.getRiskScore() : 0)
                .max().orElse(0);
        risk.setOverallRiskScore(maxScore);
        risk.setOverallRiskLevel(maxScore >= 70 ? "high" : maxScore >= 40 ? "medium" : "low");
        risk.setCriticalFindings(anomalies.stream()
                .filter(a -> "high".equals(a.getSeverity()))
                .map(QueryAnomalyDetectionResult.QueryAnomaly::getDescription)
                .collect(Collectors.toList()));
        result.setRiskAssessment(risk);

        log.debug("Anomaly detection complete: {} anomalies (H={}, M={}, L={})",
                anomalies.size(), high, medium, low);
        return result;
    }

    @Override
    public boolean detectFrequentQueries(Long userId, Long tenantId, int timeWindowMinutes, int threshold) {
        log.debug("Detecting frequent queries: userId={}, window={}min, threshold={}",
                 userId, timeWindowMinutes, threshold);
        Instant startTime = Instant.now().minusSeconds(timeWindowMinutes * 60L);
        long count = queryAuditLogMapper.countRecentQueriesByUser(tenantId, userId, startTime);
        return count > threshold;
    }

    @Override
    public List<QueryAuditLogDTO> detectSlowQueries(Long tenantId, long thresholdMs, Instant startTime, Instant endTime) {
        log.debug("Detecting slow queries: tenantId={}, threshold={}ms", tenantId, thresholdMs);
        List<QueryAuditLog> logs = queryAuditLogMapper.findSlowQueries(tenantId, thresholdMs, startTime, endTime);
        return logs.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<QueryAuditLogDTO> detectSuspiciousQueryPatterns(Long tenantId, Instant startTime, Instant endTime) {
        log.debug("Detecting suspicious query patterns: tenantId={}", tenantId);

        Instant start = startTime != null ? startTime : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant end = endTime != null ? endTime : Instant.now();

        List<QueryAuditLog> suspicious = new ArrayList<>();

        // Pattern 1: Abnormally slow queries (>5x average)
        List<QueryAuditLog> abnormallySlow = queryAuditLogMapper.findAbnormallySlowQueries(
                tenantId, start, end, 5.0, 50);
        if (abnormallySlow != null) {
            suspicious.addAll(abnormallySlow);
        }

        // Pattern 2: Very large result sets (>10x average)
        List<QueryAuditLog> largeResults = queryAuditLogMapper.findLargeResultSetQueries(
                tenantId, start, end, 10.0, 50);
        if (largeResults != null) {
            suspicious.addAll(largeResults);
        }

        // Deduplicate by ID, sort by time descending
        Set<Long> seenIds = new HashSet<>();
        return suspicious.stream()
                .filter(entry -> entry.getId() != null && seenIds.add(entry.getId()))
                .sorted(Comparator.comparing(QueryAuditLog::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // ==================== Audit Configuration ====================

    @Override
    public QueryAuditConfig getAuditConfig(Long tenantId) {
        log.debug("Getting audit config: tenantId={}", tenantId);
        return auditConfigCache.computeIfAbsent(tenantId, id -> {
            QueryAuditConfig config = new QueryAuditConfig();
            config.setTenantId(id);
            config.setAuditEnabled(true);
            config.setLogSuccessfulQueries(true);
            config.setLogFailedQueries(true);
            config.setLogSlowQueries(true);
            config.setSlowQueryThreshold(DEFAULT_SLOW_QUERY_THRESHOLD_MS);
            config.setLogSecurityEvents(true);
            config.setRetentionDays(DEFAULT_RETENTION_DAYS);
            config.setAnomalyDetectionEnabled(true);
            config.setRealtimeMonitoringEnabled(true);
            config.setEnabled(true);
            return config;
        });
    }

    @Override
    public void updateAuditConfig(Long tenantId, QueryAuditConfig config) {
        log.info("Updating audit config: tenantId={}", tenantId);
        if (config == null) {
            throw new IllegalArgumentException("Audit config must not be null");
        }
        config.setTenantId(tenantId);
        auditConfigCache.put(tenantId, config);
        log.info("Audit config updated: tenantId={}, auditEnabled={}, slowThreshold={}ms",
                tenantId, config.getAuditEnabled(), config.getSlowQueryThreshold());
    }

    @Override
    public void setAuditEnabled(Long tenantId, boolean enabled) {
        log.info("Setting audit enabled: tenantId={}, enabled={}", tenantId, enabled);
        QueryAuditConfig config = getAuditConfig(tenantId);
        config.setAuditEnabled(enabled);
        config.setEnabled(enabled);
        auditConfigCache.put(tenantId, config);
        log.info("Audit enabled updated: tenantId={}, enabled={}", tenantId, enabled);
    }

    // ==================== Data Cleanup ====================

    @Override
    public int cleanupExpiredAuditLogs(Long tenantId, int retentionDays) {
        log.info("Cleaning up expired audit logs: tenantId={}, retentionDays={}", tenantId, retentionDays);
        Instant cutoffDate = Instant.now().minusSeconds(retentionDays * 24L * 3600L);
        int deletedCount = queryAuditLogMapper.deleteExpiredLogs(tenantId, cutoffDate);
        log.info("Cleanup complete: tenantId={}, deletedCount={}", tenantId, deletedCount);
        return deletedCount;
    }

    @Override
    public int archiveAuditLogs(Long tenantId, Instant archiveBeforeDate) {
        log.info("Archiving audit logs: tenantId={}, before={}", tenantId, archiveBeforeDate);

        if (archiveBeforeDate == null) {
            archiveBeforeDate = Instant.now().minus(90, ChronoUnit.DAYS);
        }

        int archiveCount = queryAuditLogMapper.countLogsBeforeDate(tenantId, archiveBeforeDate);
        if (archiveCount == 0) {
            log.info("No audit logs to archive: tenantId={}", tenantId);
            return 0;
        }

        // In a production system this would export to cold storage before deleting.
        // For now we perform a cleanup-style archive (delete old records).
        // Callers should export/backup before invoking this method.
        int deletedCount = queryAuditLogMapper.deleteExpiredLogs(tenantId, archiveBeforeDate);
        log.info("Audit logs archived: tenantId={}, count={}", tenantId, deletedCount);
        return deletedCount;
    }

    // ==================== Report Generation ====================

    @Override
    public QueryAuditReport generateAuditReport(QueryAuditReportRequest request) {
        log.debug("Generating audit report: tenantId={}, type={}", request.getTenantId(), logSafe(request.getReportType()));

        Long tenantId = request.getTenantId();
        Instant startTime = resolveStartTime(request.getPeriodStartTime());
        Instant endTime = resolveEndTime(request.getPeriodEndTime());
        int slowThreshold = request.getSlowQueryThreshold() != null
                ? request.getSlowQueryThreshold() : DEFAULT_SLOW_QUERY_THRESHOLD_MS;

        QueryAuditReport report = new QueryAuditReport();
        report.setReportId(UUID.randomUUID().toString());
        report.setTenantId(tenantId);
        report.setTitle(request.getTitle() != null ? request.getTitle()
                : "Query Audit Report - " + request.getReportType());
        report.setReportType(request.getReportType());
        report.setPeriodStartTime(DateUtil.toUtcLocalDateTime(startTime));
        report.setPeriodEndTime(DateUtil.toUtcLocalDateTime(endTime));
        report.setGeneratedAt(LocalDateTime.now(ZoneOffset.UTC));

        // Executive Summary
        if (Boolean.TRUE.equals(request.getIncludeExecutiveSummary())) {
            report.setExecutiveSummary(buildExecutiveSummary(tenantId, startTime, endTime, slowThreshold));
        }

        // Query Activity Overview
        report.setQueryActivityOverview(buildQueryActivityOverview(tenantId, startTime, endTime));

        // Performance Analysis
        if (Boolean.TRUE.equals(request.getIncludePerformanceAnalysis())) {
            report.setPerformanceAnalysis(buildPerformanceAnalysis(tenantId, startTime, endTime, slowThreshold));
        }

        // Security Analysis
        if (Boolean.TRUE.equals(request.getIncludeSecurityAnalysis())) {
            report.setSecurityAnalysis(buildSecurityAnalysis(tenantId, startTime, endTime));
        }

        // User Activity Analysis
        if (Boolean.TRUE.equals(request.getIncludeUserActivityAnalysis())) {
            report.setUserActivityAnalysis(buildUserActivityAnalysis(tenantId, startTime, endTime));
        }

        // Model Usage Analysis
        if (Boolean.TRUE.equals(request.getIncludeModelUsageAnalysis())) {
            report.setModelUsageAnalysis(buildModelUsageAnalysis(tenantId, startTime, endTime));
        }

        // Recommendations
        if (Boolean.TRUE.equals(request.getIncludeRecommendations())) {
            report.setRecommendations(buildRecommendations(tenantId, startTime, endTime, slowThreshold));
        }

        // Report configuration metadata
        QueryAuditReport.ReportConfiguration reportConfig = new QueryAuditReport.ReportConfiguration();
        reportConfig.setReportFormat(request.getReportFormat());
        reportConfig.setDetailLevel(request.getDetailLevel());
        report.setConfiguration(reportConfig);

        log.debug("Audit report generated: reportId={}", logSafe(report.getReportId()));
        return report;
    }

    @Override
    public QueryAuditExportResult exportAuditLogs(QueryAuditExportRequest request) {
        log.debug("Exporting audit logs: tenantId={}, format={}", request.getTenantId(), logSafe(request.getExportFormat()));

        Instant exportStart = Instant.now();
        Long tenantId = request.getTenantId();
        Instant startTime = DateUtil.toUtcInstant(request.getStartTime());
        Instant endTime = DateUtil.toUtcInstant(request.getEndTime());

        // Determine filters
        Boolean successFilter = null;
        if (Boolean.TRUE.equals(request.getSuccessfulQueriesOnly())) {
            successFilter = true;
        } else if (Boolean.TRUE.equals(request.getFailedQueriesOnly())) {
            successFilter = false;
        }
        String modelCodeFilter = (request.getModelCodes() != null && request.getModelCodes().size() == 1)
                ? request.getModelCodes().get(0) : null;

        // Count total records
        long totalRecords = queryAuditLogMapper.countForExport(
                tenantId, startTime, endTime, successFilter, modelCodeFilter);

        QueryAuditExportResult result = new QueryAuditExportResult();
        result.setExportTaskId(UUID.randomUUID().toString());
        result.setTenantId(tenantId);
        result.setStartTime(DateUtil.toUtcLocalDateTime(exportStart));
        result.setTotalRecords(totalRecords);

        int maxRecords = request.getMaxRecords() != null ? request.getMaxRecords() : 100000;
        long effectiveTotal = Math.min(totalRecords, maxRecords);
        int batchSize = request.getPageSize() != null ? request.getPageSize() : MAX_EXPORT_BATCH_SIZE;
        int batches = (int) Math.ceil((double) effectiveTotal / batchSize);

        // Iterate and collect statistics
        long exportedCount = 0;
        long successfulCount = 0;
        long failedCount = 0;
        long slowCount = 0;
        double totalExecTime = 0;
        int maxExecTime = 0;
        int minExecTime = Integer.MAX_VALUE;
        Map<String, Long> queryTypeDist = new HashMap<>();
        Map<String, Long> modelDist = new HashMap<>();
        Instant earliest = null;
        Instant latest = null;

        for (int batch = 0; batch < batches; batch++) {
            List<QueryAuditLog> records = queryAuditLogMapper.fetchBatchForExport(
                    tenantId, startTime, endTime, successFilter, modelCodeFilter,
                    batchSize, batch * batchSize);

            for (QueryAuditLog record : records) {
                exportedCount++;
                if (Boolean.TRUE.equals(record.getSuccess())) {
                    successfulCount++;
                } else {
                    failedCount++;
                }

                int execTime = record.getExecutionTimeMs() != null ? record.getExecutionTimeMs()
                        : (record.getCostMs() != null ? record.getCostMs() : 0);
                totalExecTime += execTime;
                maxExecTime = Math.max(maxExecTime, execTime);
                if (execTime > 0) minExecTime = Math.min(minExecTime, execTime);
                if (execTime >= DEFAULT_SLOW_QUERY_THRESHOLD_MS) slowCount++;

                if (record.getQueryType() != null) {
                    queryTypeDist.merge(record.getQueryType(), 1L, Long::sum);
                }
                if (record.getModelCode() != null) {
                    modelDist.merge(record.getModelCode(), 1L, Long::sum);
                }
                if (record.getCreatedAt() != null) {
                    if (earliest == null || record.getCreatedAt().isBefore(earliest)) earliest = record.getCreatedAt();
                    if (latest == null || record.getCreatedAt().isAfter(latest)) latest = record.getCreatedAt();
                }
            }
        }

        // Statistics
        QueryAuditExportResult.ExportStatistics stats = new QueryAuditExportResult.ExportStatistics();
        stats.setTotalQueries(exportedCount);
        stats.setSuccessfulQueries(successfulCount);
        stats.setFailedQueries(failedCount);
        stats.setSlowQueries(slowCount);
        stats.setQueryTypeDistribution(queryTypeDist);
        stats.setModelDistribution(modelDist);
        stats.setAverageExecutionTime(exportedCount > 0 ? totalExecTime / exportedCount : 0.0);
        stats.setMaxExecutionTime(maxExecTime);
        stats.setMinExecutionTime(minExecTime == Integer.MAX_VALUE ? 0 : minExecTime);
        if (earliest != null) stats.setEarliestQueryTime(DateUtil.toUtcLocalDateTime(earliest));
        if (latest != null) stats.setLatestQueryTime(DateUtil.toUtcLocalDateTime(latest));
        result.setStatistics(stats);

        // Finalize
        Instant exportEnd = Instant.now();
        result.setEndTime(DateUtil.toUtcLocalDateTime(exportEnd));
        result.setDurationMs(java.time.Duration.between(exportStart, exportEnd).toMillis());
        result.setExportedRecords(exportedCount);
        result.setProgress(100.0);
        result.setStatus(StatusConstants.COMPLETED);
        result.setSuccess(true);

        QueryAuditExportResult.ExportExecutionInfo execInfo =
                new QueryAuditExportResult.ExportExecutionInfo();
        execInfo.setExportVersion("1.0");
        execInfo.setBatchSize(batchSize);
        execInfo.setTotalBatches(batches);
        execInfo.setProcessedBatches(batches);
        result.setExecutionInfo(execInfo);

        log.info("Export complete: tenantId={}, records={}, duration={}ms",
                tenantId, exportedCount, result.getDurationMs());
        return result;
    }

    // ==================== Report Building Helpers ====================

    private QueryAuditReport.ExecutiveSummary buildExecutiveSummary(
            Long tenantId, Instant startTime, Instant endTime, int slowThreshold) {

        QueryAuditReport.ExecutiveSummary summary = new QueryAuditReport.ExecutiveSummary();

        Long total = queryAuditLogMapper.countByTenantAndTimeRange(tenantId, startTime, endTime);
        Long successful = queryAuditLogMapper.countSuccessfulQueries(tenantId, startTime, endTime);
        Long slow = queryAuditLogMapper.countSlowQueries(tenantId, slowThreshold, startTime, endTime);
        Double avgTime = queryAuditLogMapper.calculateAverageExecutionTime(tenantId, startTime, endTime);

        summary.setTotalQueries(nullSafe(total));
        summary.setSuccessRate(safePercentage(nullSafe(successful), nullSafe(total)));
        summary.setAverageResponseTime(avgTime != null ? avgTime : 0.0);

        double successRate = summary.getSuccessRate() != null ? summary.getSuccessRate() : 0;
        double avgResp = summary.getAverageResponseTime() != null ? summary.getAverageResponseTime() : 0;
        long slowCount = nullSafe(slow);
        long totalCount = nullSafe(total);

        int criticalIssues = 0;
        int warnings = 0;
        List<String> keyFindings = new ArrayList<>();
        List<String> immediateActions = new ArrayList<>();

        if (successRate < 95) {
            criticalIssues++;
            keyFindings.add(String.format("Success rate below 95%%: %.1f%%", successRate));
            immediateActions.add("Investigate failing queries");
        }
        if (avgResp > 3000) {
            warnings++;
            keyFindings.add(String.format("Average response time exceeds 3s: %.0fms", avgResp));
            immediateActions.add("Review and optimize slow queries");
        }
        if (totalCount > 0 && (double) slowCount / totalCount > 0.1) {
            warnings++;
            keyFindings.add("Slow query rate exceeds 10%");
        }

        summary.setCriticalIssues(criticalIssues);
        summary.setWarnings(warnings);
        summary.setKeyFindings(keyFindings);
        summary.setImmediateActions(immediateActions);
        summary.setOverallHealthStatus(
                criticalIssues > 0 ? "critical" : warnings > 0 ? "warning" : "healthy");

        return summary;
    }

    private QueryAuditReport.QueryActivityOverview buildQueryActivityOverview(
            Long tenantId, Instant startTime, Instant endTime) {

        QueryAuditReport.QueryActivityOverview overview = new QueryAuditReport.QueryActivityOverview();
        overview.setTotalQueries(nullSafe(
                queryAuditLogMapper.countByTenantAndTimeRange(tenantId, startTime, endTime)));
        overview.setSuccessfulQueries(nullSafe(
                queryAuditLogMapper.countSuccessfulQueries(tenantId, startTime, endTime)));
        overview.setFailedQueries(nullSafe(
                queryAuditLogMapper.countFailedQueries(tenantId, startTime, endTime)));
        overview.setQueryTypeDistribution(mapToStringLong(
                queryAuditLogMapper.countByQueryType(tenantId, startTime, endTime), "query_type"));
        overview.setDailyQueryCounts(mapToStringLong(
                queryAuditLogMapper.countByDate(tenantId, startTime, endTime), "date"));
        overview.setHourlyQueryCounts(mapToIntLong(
                queryAuditLogMapper.countByHour(tenantId, startTime, endTime), "hour"));

        List<Map<String, Object>> modelCounts = queryAuditLogMapper.countByModel(tenantId, startTime, endTime);
        if (modelCounts != null) {
            overview.setMostQueriedModels(modelCounts.stream()
                    .limit(5).map(m -> String.valueOf(m.get("model_code")))
                    .collect(Collectors.toList()));
        }
        List<Map<String, Object>> userCounts = queryAuditLogMapper.countByUser(tenantId, startTime, endTime);
        if (userCounts != null) {
            overview.setMostActiveUsers(userCounts.stream()
                    .limit(5).map(m -> String.valueOf(m.get("user_id")))
                    .collect(Collectors.toList()));
        }

        return overview;
    }

    private QueryAuditReport.PerformanceAnalysis buildPerformanceAnalysis(
            Long tenantId, Instant startTime, Instant endTime, int slowThreshold) {

        QueryAuditReport.PerformanceAnalysis perf = new QueryAuditReport.PerformanceAnalysis();
        perf.setAverageExecutionTime(nullToZero(
                queryAuditLogMapper.calculateAverageExecutionTime(tenantId, startTime, endTime)));
        perf.setMedianExecutionTime(nullToZero(
                queryAuditLogMapper.calculatePercentileExecutionTime(tenantId, 0.5, startTime, endTime)));
        perf.setP95ExecutionTime(nullToZero(
                queryAuditLogMapper.calculatePercentileExecutionTime(tenantId, 0.95, startTime, endTime)));
        perf.setP99ExecutionTime(nullToZero(
                queryAuditLogMapper.calculatePercentileExecutionTime(tenantId, 0.99, startTime, endTime)));

        Long total = queryAuditLogMapper.countByTenantAndTimeRange(tenantId, startTime, endTime);
        perf.setSlowQueryCount(nullSafe(
                queryAuditLogMapper.countSlowQueries(tenantId, slowThreshold, startTime, endTime)));
        perf.setSlowQueryRate(safePercentage(perf.getSlowQueryCount(), nullSafe(total)));

        List<QueryAuditLog> slowLogs = queryAuditLogMapper.getSlowQueries(
                tenantId, slowThreshold, startTime, endTime, 5);
        if (slowLogs != null) {
            perf.setTopSlowQueries(slowLogs.stream().map(entry -> {
                QueryAuditReport.SlowQuerySummary sq = new QueryAuditReport.SlowQuerySummary();
                sq.setQueryId(entry.getQueryId());
                sq.setModelCode(entry.getModelCode());
                sq.setExecutionTime(entry.getExecutionTimeMs() != null
                        ? entry.getExecutionTimeMs() : entry.getCostMs());
                sq.setOptimizationSuggestion("Consider adding indexes or simplifying conditions");
                return sq;
            }).collect(Collectors.toList()));
        }

        return perf;
    }

    private QueryAuditReport.SecurityAnalysis buildSecurityAnalysis(
            Long tenantId, Instant startTime, Instant endTime) {

        QueryAuditReport.SecurityAnalysis security = new QueryAuditReport.SecurityAnalysis();
        Map<String, Long> errorTypes = mapToStringLong(
                queryAuditLogMapper.countByErrorType(tenantId, startTime, endTime), "error_type");

        long secEventCount = 0;
        if (errorTypes != null) {
            secEventCount = errorTypes.entrySet().stream()
                    .filter(e -> "PermissionDenied".equals(e.getKey())
                            || "SecurityValidationFailure".equals(e.getKey()))
                    .mapToLong(Map.Entry::getValue).sum();
        }

        security.setSecurityEventCount(secEventCount);
        security.setSecurityEventTypes(errorTypes != null ? errorTypes : new HashMap<>());
        security.setDataMaskingApplications(nullSafe(
                queryAuditLogMapper.countDataMaskingApplications(tenantId, startTime, endTime)));

        QueryAuditReport.SecurityRiskAssessment risk = new QueryAuditReport.SecurityRiskAssessment();
        risk.setRiskScore(secEventCount > 10 ? 80 : secEventCount > 0 ? 40 : 10);
        risk.setOverallRiskLevel(secEventCount > 10 ? "high" : secEventCount > 0 ? "medium" : "low");
        risk.setRiskFactors(new ArrayList<>());
        if (secEventCount > 0) {
            risk.getRiskFactors().add("Permission denied events: " + secEventCount);
        }
        security.setRiskAssessment(risk);

        return security;
    }

    private QueryAuditReport.UserActivityAnalysis buildUserActivityAnalysis(
            Long tenantId, Instant startTime, Instant endTime) {

        QueryAuditReport.UserActivityAnalysis analysis = new QueryAuditReport.UserActivityAnalysis();
        analysis.setActiveUserCount(nullSafe(
                queryAuditLogMapper.countUniqueUsers(tenantId, startTime, endTime)));
        analysis.setUserQueryCounts(mapToLongLong(
                queryAuditLogMapper.countByUser(tenantId, startTime, endTime), "user_id"));
        return analysis;
    }

    private QueryAuditReport.ModelUsageAnalysis buildModelUsageAnalysis(
            Long tenantId, Instant startTime, Instant endTime) {

        QueryAuditReport.ModelUsageAnalysis analysis = new QueryAuditReport.ModelUsageAnalysis();
        analysis.setActiveModelCount(nullSafe(
                queryAuditLogMapper.countUniqueModels(tenantId, startTime, endTime)));
        analysis.setModelQueryCounts(mapToStringLong(
                queryAuditLogMapper.countByModel(tenantId, startTime, endTime), "model_code"));
        return analysis;
    }

    private List<QueryAuditReport.RecommendationItem> buildRecommendations(
            Long tenantId, Instant startTime, Instant endTime, int slowThreshold) {

        List<QueryAuditReport.RecommendationItem> recs = new ArrayList<>();

        long totalCount = nullSafe(queryAuditLogMapper.countByTenantAndTimeRange(tenantId, startTime, endTime));
        long failedCount = nullSafe(queryAuditLogMapper.countFailedQueries(tenantId, startTime, endTime));
        long slowCount = nullSafe(queryAuditLogMapper.countSlowQueries(tenantId, slowThreshold, startTime, endTime));
        double avgTime = nullToZero(queryAuditLogMapper.calculateAverageExecutionTime(tenantId, startTime, endTime));

        if (totalCount > 0 && (double) failedCount / totalCount > 0.05) {
            QueryAuditReport.RecommendationItem item = new QueryAuditReport.RecommendationItem();
            item.setCategory("Reliability");
            item.setTitle("Reduce query failure rate");
            item.setPriority("high");
            item.setDescription(String.format("%.1f%% of queries are failing. Target: below 5%%.",
                    (double) failedCount / totalCount * 100));
            item.setActionSteps(List.of(
                    "Review error type distribution", "Fix top error categories",
                    "Add input validation to prevent invalid queries"));
            item.setExpectedOutcome("Reduced error rate and improved user experience");
            recs.add(item);
        }

        if (totalCount > 0 && (double) slowCount / totalCount > 0.1) {
            QueryAuditReport.RecommendationItem item = new QueryAuditReport.RecommendationItem();
            item.setCategory("Performance");
            item.setTitle("Optimize slow queries");
            item.setPriority("high");
            item.setDescription(String.format("%.1f%% of queries exceed %dms threshold.",
                    (double) slowCount / totalCount * 100, slowThreshold));
            item.setActionSteps(List.of(
                    "Analyze top slow queries", "Add database indexes",
                    "Consider query caching for repeated patterns"));
            item.setExpectedOutcome("Faster query response times");
            recs.add(item);
        }

        if (avgTime > 2000) {
            QueryAuditReport.RecommendationItem item = new QueryAuditReport.RecommendationItem();
            item.setCategory("Performance");
            item.setTitle("Improve average query response time");
            item.setPriority("medium");
            item.setDescription(String.format("Average response time is %.0fms. Target: below 1000ms.", avgTime));
            item.setActionSteps(List.of(
                    "Enable query result caching",
                    "Review database connection pool configuration",
                    "Consider read replicas for heavy read workloads"));
            item.setExpectedOutcome("Sub-second average response times");
            recs.add(item);
        }

        if (recs.isEmpty()) {
            QueryAuditReport.RecommendationItem item = new QueryAuditReport.RecommendationItem();
            item.setCategory("Best Practice");
            item.setTitle("Continue monitoring");
            item.setPriority("low");
            item.setDescription("Query performance is within acceptable thresholds.");
            item.setActionSteps(List.of("Review this report periodically",
                    "Set up alerting for performance degradation"));
            item.setExpectedOutcome("Proactive issue detection");
            recs.add(item);
        }

        return recs;
    }

    // ==================== Private Utility Methods ====================

    private void setRequestInfo(QueryAuditLog auditLog) {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                auditLog.setIpAddress(getClientIpAddress(request));

                String userAgent = request.getHeader("User-Agent");
                if (StringUtils.hasText(userAgent) && userAgent.length() > 500) {
                    userAgent = userAgent.substring(0, 500);
                }
                auditLog.setUserAgent(userAgent);
                auditLog.setRequestId(request.getHeader("X-Request-ID"));

                String sessionId = request.getSession(false) != null
                        ? request.getSession().getId() : null;
                auditLog.setSessionId(sessionId);
            }
        } catch (Exception e) {
            log.warn("Failed to set request info: {}", logSafe(e.getMessage()));
        }
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String[] headerNames = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP",
            "WL-Proxy-Client-IP", "http_client_ip", "http_x_forwarded_for"
        };
        for (String headerName : headerNames) {
            String ip = request.getHeader(headerName);
            if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
                if (ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        }
        return request.getRemoteAddr();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", logSafe(e.getMessage()));
            return null;
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    private QueryAuditLogDTO convertToDTO(QueryAuditLog entity) {
        if (entity == null) return null;

        QueryAuditLogDTO dto = new QueryAuditLogDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setUserId(entity.getUserId());
        dto.setQueryId(entity.getQueryId());
        dto.setQueryName(entity.getQueryName());
        dto.setModelCode(entity.getModelCode());
        dto.setQueryType(entity.getQueryType());
        dto.setExecutionTimeMs(entity.getExecutionTimeMs() != null
                ? entity.getExecutionTimeMs() : entity.getCostMs());
        dto.setResultCount(entity.getResultCount());
        dto.setSuccess(entity.getSuccess());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setErrorType(entity.getErrorType());
        dto.setIpAddress(entity.getIpAddress());
        dto.setUserAgent(entity.getUserAgent());
        dto.setRequestId(entity.getRequestId());
        dto.setSessionId(entity.getSessionId());
        dto.setCacheHit(entity.getCacheHit());
        dto.setDataMaskingApplied(entity.getDataMaskingApplied());
        dto.setCreatedAt(DateUtil.toUtcLocalDateTime(entity.getCreatedAt()));
        return dto;
    }

    // ==================== Time Resolution ====================

    private Instant resolveStartTime(LocalDateTime startTime) {
        return startTime != null ? DateUtil.toUtcInstant(startTime)
                : Instant.now().minus(30, ChronoUnit.DAYS);
    }

    private Instant resolveEndTime(LocalDateTime endTime) {
        return endTime != null ? DateUtil.toUtcInstant(endTime) : Instant.now();
    }

    // ==================== Null-Safe Helpers ====================

    private Double safePercentage(Long numerator, Long denominator) {
        if (denominator == null || denominator == 0L) return 0.0;
        return (double) numerator / denominator * 100.0;
    }

    private Long nullSafe(Long value) {
        return value != null ? value : 0L;
    }

    private Double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }

    private Integer nullToZeroInt(Integer value) {
        return value != null ? value : 0;
    }

    // ==================== Map Conversion Helpers ====================

    private Map<String, Long> mapToStringLong(List<Map<String, Object>> rows, String keyColumn) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get(keyColumn)), toLong(row.get("count")));
        }
        return result;
    }

    private Map<Long, Long> mapToLongLong(List<Map<String, Object>> rows, String keyColumn) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        Map<Long, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(toLong(row.get(keyColumn)), toLong(row.get("count")));
        }
        return result;
    }

    private Map<Integer, Long> mapToIntLong(List<Map<String, Object>> rows, String keyColumn) {
        if (rows == null || rows.isEmpty()) return new LinkedHashMap<>();
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(toInt(row.get(keyColumn)), toLong(row.get("count")));
        }
        return result;
    }

    private Long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0L; }
    }

    private Integer toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0; }
    }

    private Double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
