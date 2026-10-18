package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.QueryAccessCheckResult;
import com.auraboot.framework.meta.dto.QueryAuditLogDTO;
import com.auraboot.framework.meta.dto.QueryAuditLogQueryRequest;
import com.auraboot.framework.meta.dto.QueryAuditStatistics;
import com.auraboot.framework.meta.dto.QueryAuditStatisticsRequest;
import com.auraboot.framework.meta.dto.QueryAuditConfig;
import com.auraboot.framework.meta.dto.QuerySecurityValidationResult;
import com.auraboot.framework.meta.dto.QueryType;
import com.auraboot.framework.meta.dto.SecureQueryRequest;
import com.auraboot.framework.meta.entity.QueryAuditLog;
import com.auraboot.framework.meta.mapper.QueryAuditLogMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueryAuditServiceImpl 分支覆盖单元测试（无 DB）：四类审计日志写入的分支
 * 树（成功/错误/权限拒绝/安全校验失败，含异常吞咽语义）、审计查询分页映射、
 * 统计聚合（可选小时/日维度 + 安全违规派生）、检测器与配置缓存生命周期、
 * 数据清理与归档。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QueryAuditServiceImpl 分支覆盖")
class QueryAuditServiceImplTest {

    @Mock
    private QueryAuditLogMapper queryAuditLogMapper;
    @org.mockito.Spy
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private Tracer tracer;

    @InjectMocks
    private QueryAuditServiceImpl service;

    private SecureQueryRequest request;

    @BeforeEach
    void setUp() {
        request = new SecureQueryRequest();
        request.setModelCode("crm_customer");
        request.setQueryType(QueryType.SELECT_PAGE);
        request.setUserId(7L);
        request.setTenantId(42L);
        request.setQueryId("q-1");
        request.setEnableCache(true);
        request.setEnableDataMasking(false);
    }

    @AfterEach
    void tearDown() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }

    private QueryAuditLog logged() {
        ArgumentCaptor<QueryAuditLog> captor = ArgumentCaptor.forClass(QueryAuditLog.class);
        verify(queryAuditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("执行日志")
    class ExecutionLog {

        @Test
        @DisplayName("完整分支：条件/选择/排序/分页/分页结果计数/缓存掩码标记")
        void fullBranchLogging() {
            request.setConditions(List.of(com.auraboot.framework.meta.dto.QueryCondition.builder()
        .fieldName("amount")
        .value(100)
        .build()));
            request.setSelectFields(List.of("name", "amount"));
            request.setSortFields(List.of());
            request.setPagination(new com.auraboot.framework.meta.dto.PaginationRequest());
            request.setEnableCache(true);
            request.setEnableDataMasking(true);

            service.logQueryExecution(request, new com.auraboot.framework.meta.dto.PaginationResult(List.of(), 37L, 10, 20), 123L);

            QueryAuditLog log = logged();
            assertThat(log.getTenantId()).isEqualTo(42L);
            assertThat(log.getUserId()).isEqualTo(7L);
            assertThat(log.getModelCode()).isEqualTo("crm_customer");
            assertThat(log.getQueryType()).isEqualTo("SELECT_PAGE");
            assertThat(log.getSuccess()).isTrue();
            assertThat(log.getRejected()).isFalse();
            assertThat(log.getResultCount()).isEqualTo(37);
            assertThat(log.getCacheHit()).isTrue();
            assertThat(log.getDataMaskingApplied()).isTrue();
            assertThat(log.getQueryConditions()).contains("amount");
            assertThat(log.getSelectFields()).contains("name");
            assertThat(log.getPaginationInfo()).isNotNull();
        }

        @Test
        @DisplayName("List 结果按 size 计数；单对象计 1")
        void resultCountVariants() {
            service.logQueryExecution(request, List.of("a", "b", "c"), 5L);
            assertThat(logged().getResultCount()).isEqualTo(3);

            ArgumentCaptor<QueryAuditLog> captor = ArgumentCaptor.forClass(QueryAuditLog.class);
            service.logQueryExecution(request, new Object(), 5L);
            captor = ArgumentCaptor.forClass(QueryAuditLog.class);
            verify(queryAuditLogMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
            assertThat(captor.getAllValues().get(1).getResultCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("写入异常被吞咽（审计失败不影响业务链路）")
        void insertFailureSwallowed() {
            when(queryAuditLogMapper.insert(any(QueryAuditLog.class)))
                    .thenThrow(new IllegalStateException("db down"));
            service.logQueryExecution(request, List.of(), 1L);
            // no exception escaped
            verify(queryAuditLogMapper).insert(any(QueryAuditLog.class));
        }
    }

    @Nested
    @DisplayName("错误与安全日志")
    class ErrorAndSecurityLogs {

        @Test
        @DisplayName("错误日志：success=false + 错误类型/消息")
        void errorLogRecordsFailure() {
            service.logQueryError(request, new IllegalStateException("boom"), 88L);

            QueryAuditLog log = logged();
            assertThat(log.getSuccess()).isFalse();
            assertThat(log.getErrorType()).isEqualTo("IllegalStateException");
            assertThat(log.getErrorMessage()).isEqualTo("boom");
            assertThat(log.getExecutionTimeMs()).isEqualTo(88);
        }

        @Test
        @DisplayName("权限拒绝才落日志；放行不写")
        void permissionDeniedOnly() {
            QueryAccessCheckResult denied = new QueryAccessCheckResult();
            denied.setHasAccess(false);
            denied.setDenyReason("model.read missing");
            service.logPermissionCheck(request, denied);

            QueryAuditLog log = logged();
            assertThat(log.getRejected()).isTrue();
            assertThat(log.getRejectReason()).contains("model.read missing");
            assertThat(log.getErrorType()).isEqualTo("PermissionDenied");

            QueryAccessCheckResult allowed = new QueryAccessCheckResult();
            allowed.setHasAccess(true);
            service.logPermissionCheck(request, allowed);
            verify(queryAuditLogMapper, org.mockito.Mockito.times(1)).insert(any(QueryAuditLog.class));
        }

        @Test
        @DisplayName("安全校验失败：issues 拼接进拒绝原因；通过不写")
        void securityValidationFailure() {
            QuerySecurityValidationResult result = new QuerySecurityValidationResult();
            result.setValid(false);
            QuerySecurityValidationResult.SecurityIssue issue = new QuerySecurityValidationResult.SecurityIssue();
            issue.setType("SQL_INJECTION");
            issue.setDescription("suspicious token");
            result.setSecurityIssues(List.of(issue));
            service.logSecurityValidation(request, result);

            QueryAuditLog log = logged();
            assertThat(log.getQueryType()).isEqualTo("security_validation");
            assertThat(log.getRejectReason()).contains("SQL_INJECTION").contains("suspicious token");
            assertThat(log.getErrorType()).isEqualTo("SecurityValidationFailure");

            QuerySecurityValidationResult passing = new QuerySecurityValidationResult();
            passing.setValid(true);
            service.logSecurityValidation(request, passing);
            verify(queryAuditLogMapper, org.mockito.Mockito.times(1)).insert(any(QueryAuditLog.class));
        }
    }

    @Nested
    @DisplayName("审计查询")
    class AuditQueries {

        @Test
        @DisplayName("分页查询映射 PageResult")
        void pagedQueryMapped() {
            QueryAuditLog entity = new QueryAuditLog();
            entity.setId(1L);
            entity.setTenantId(42L);
            entity.setModelCode("crm_customer");
            Page<QueryAuditLog> page = new Page<>(1, 20);
            page.setRecords(List.of(entity));
            page.setTotal(9);
            when(queryAuditLogMapper.selectPageList(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(page);

            QueryAuditLogQueryRequest request = new QueryAuditLogQueryRequest();
            request.setTenantId(42L);
            request.setPage(1);
            request.setSize(20);
            request.setStartTime(LocalDateTime.of(2026, 1, 1, 0, 0));
            request.setEndTime(LocalDateTime.of(2026, 2, 1, 0, 0));
            PageResult<QueryAuditLogDTO> result = service.queryAuditLogs(request);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getId()).isEqualTo(1L);
            assertThat(result.getTotal()).isEqualTo(9);
        }

        @Test
        @DisplayName("慢查询检测映射 DTO 列表")
        void slowQueriesMapped() {
            QueryAuditLog entity = new QueryAuditLog();
            entity.setId(2L);
            entity.setExecutionTimeMs(9000);
            when(queryAuditLogMapper.findSlowQueries(any(), anyLong(), any(), any()))
                    .thenReturn(List.of(entity));
            List<QueryAuditLogDTO> slow = service.detectSlowQueries(42L, 5000, Instant.EPOCH, Instant.now());
            assertThat(slow).hasSize(1);
            assertThat(slow.get(0).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("可疑模式去重并按时间倒序")
        void suspiciousPatternsDeduplicated() {
            QueryAuditLog first = new QueryAuditLog();
            first.setId(1L);
            first.setCreatedAt(Instant.now().minusSeconds(60));
            QueryAuditLog duplicate = new QueryAuditLog();
            duplicate.setId(1L);
            duplicate.setCreatedAt(Instant.now());
            when(queryAuditLogMapper.findAbnormallySlowQueries(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(first, duplicate));
            when(queryAuditLogMapper.findLargeResultSetQueries(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            List<QueryAuditLogDTO> suspicious = service.detectSuspiciousQueryPatterns(42L, null, null);
            assertThat(suspicious).hasSize(1);
            assertThat(suspicious.get(0).getId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("统计聚合")
    class Statistics {

        private void stubCounters() {
            when(queryAuditLogMapper.countByTenantAndTimeRange(any(), any(), any())).thenReturn(100L);
            when(queryAuditLogMapper.countSuccessfulQueries(any(), any(), any())).thenReturn(95L);
            when(queryAuditLogMapper.countFailedQueries(any(), any(), any())).thenReturn(5L);
            when(queryAuditLogMapper.calculateAverageExecutionTime(any(), any(), any())).thenReturn(null);
            when(queryAuditLogMapper.getMaxExecutionTime(any(), any(), any())).thenReturn(null);
            when(queryAuditLogMapper.getMinExecutionTime(any(), any(), any())).thenReturn(null);
            when(queryAuditLogMapper.countSlowQueries(any(), any(), any(), any())).thenReturn(3L);
            when(queryAuditLogMapper.countCacheHits(any(), any(), any())).thenReturn(40L);
            when(queryAuditLogMapper.countDataMaskingApplications(any(), any(), any())).thenReturn(7L);
            when(queryAuditLogMapper.countUniqueUsers(any(), any(), any())).thenReturn(6L);
            when(queryAuditLogMapper.countUniqueModels(any(), any(), any())).thenReturn(4L);
            when(queryAuditLogMapper.sumTotalRecordsReturned(any(), any(), any())).thenReturn(1234L);
            when(queryAuditLogMapper.countByQueryType(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByModel(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByUser(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByErrorType(any(), any(), any())).thenReturn(List.of(
                    Map.of("error_type", "PermissionDenied", "count", 2L),
                    Map.of("error_type", "SecurityValidationFailure", "count", 1L),
                    Map.of("error_type", "Other", "count", 5L)));
            when(queryAuditLogMapper.getExecutionTimeDistribution(any(), any(), any())).thenReturn(List.of());
        }

        @Test
        @DisplayName("聚合计数/比率/安全违规派生")
        void statisticsAggregated() {
            stubCounters();
            QueryAuditStatisticsRequest request = new QueryAuditStatisticsRequest();
            request.setTenantId(42L);
            request.setSlowQueryThreshold(2500);

            QueryAuditStatistics stats = service.getQueryStatistics(request);

            assertThat(stats.getTotalQueries()).isEqualTo(100);
            assertThat(stats.getSuccessfulQueries()).isEqualTo(95);
            assertThat(stats.getSuccessRate()).isEqualTo(95.0);
            assertThat(stats.getSlowQueryThreshold()).isEqualTo(2500);
            assertThat(stats.getCacheHitRate()).isEqualTo(40.0);
            // 安全违规只统计两类安全错误型
            assertThat(stats.getSecurityViolationCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("可选小时/日维度开关")
        void hourlyAndDailyToggles() {
            stubCounters();
            when(queryAuditLogMapper.countByHour(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByDate(any(), any(), any())).thenReturn(List.of());

            QueryAuditStatisticsRequest with = new QueryAuditStatisticsRequest();
            with.setTenantId(42L);
            with.setIncludeHourlyStats(true);
            with.setIncludeDailyStats(true);
            QueryAuditStatistics stats = service.getQueryStatistics(with);
            assertThat(stats.getHourlyStatistics()).isNotNull();
            assertThat(stats.getDailyStatistics()).isNotNull();
            verify(queryAuditLogMapper).countByHour(any(), any(), any());
            verify(queryAuditLogMapper).countByDate(any(), any(), any());
        }

        @Test
        @DisplayName("频次检测：计数超阈值才告警")
        void frequentQueriesThreshold() {
            when(queryAuditLogMapper.countRecentQueriesByUser(any(), any(), any())).thenReturn(11L);
            assertThat(service.detectFrequentQueries(7L, 42L, 10, 10)).isTrue();
            when(queryAuditLogMapper.countRecentQueriesByUser(any(), any(), any())).thenReturn(5L);
            assertThat(service.detectFrequentQueries(7L, 42L, 10, 10)).isFalse();
        }
    }

    @Nested
    @DisplayName("配置与清理")
    class ConfigAndCleanup {

        @Test
        @DisplayName("默认配置缓存化；更新与开关生效")
        void configLifecycle() {
            QueryAuditConfig config = service.getAuditConfig(42L);
            assertThat(config.getAuditEnabled()).isTrue();
            assertThat(config.getSlowQueryThreshold()).isEqualTo(5000);
            assertThat(config.getRetentionDays()).isEqualTo(90);
            // 同租户二次读取来自缓存（同一实例）
            assertThat(service.getAuditConfig(42L)).isSameAs(config);

            service.setAuditEnabled(42L, false);
            assertThat(service.getAuditConfig(42L).getAuditEnabled()).isFalse();

            QueryAuditConfig custom = new QueryAuditConfig();
            custom.setSlowQueryThreshold(1200);
            service.updateAuditConfig(42L, custom);
            assertThat(service.getAuditConfig(42L).getSlowQueryThreshold()).isEqualTo(1200);
            assertThatThrownBy(() -> service.updateAuditConfig(42L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("过期清理与归档（空集短路）")
        void cleanupAndArchive() {
            when(queryAuditLogMapper.deleteExpiredLogs(any(), any())).thenReturn(17);
            assertThat(service.cleanupExpiredAuditLogs(42L, 90)).isEqualTo(17);

            when(queryAuditLogMapper.countLogsBeforeDate(eq(42L), any())).thenReturn(0);
            assertThat(service.archiveAuditLogs(42L, null)).isZero();
            verify(queryAuditLogMapper, never()).deleteExpiredLogs(eq(42L), eq(null));

            when(queryAuditLogMapper.countLogsBeforeDate(eq(42L), any())).thenReturn(5);
            when(queryAuditLogMapper.deleteExpiredLogs(eq(42L), any())).thenReturn(5);
            assertThat(service.archiveAuditLogs(42L, Instant.EPOCH)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("用户统计 / 报告 / 导出")
    class ReportsAndExports {

        private void stubAllLists() {
            when(queryAuditLogMapper.countByQueryType(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByModel(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByUser(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByErrorType(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByHour(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByDate(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.getExecutionTimeDistribution(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countUserByQueryType(any(), any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countUserByModel(any(), any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.findByUserId(any(), any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.findAbnormallySlowQueries(any(), any(), any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.findLargeResultSetQueries(any(), any(), any(), any(), any())).thenReturn(List.of());
        }

        private void stubCoreCounters() {
            when(queryAuditLogMapper.countByTenantAndTimeRange(any(), any(), any())).thenReturn(100L);
            when(queryAuditLogMapper.countSuccessfulQueries(any(), any(), any())).thenReturn(95L);
            when(queryAuditLogMapper.countFailedQueries(any(), any(), any())).thenReturn(5L);
            when(queryAuditLogMapper.countSlowQueries(any(), any(), any(), any())).thenReturn(3L);
            when(queryAuditLogMapper.countCacheHits(any(), any(), any())).thenReturn(40L);
            when(queryAuditLogMapper.countDataMaskingApplications(any(), any(), any())).thenReturn(4L);
            when(queryAuditLogMapper.countUniqueUsers(any(), any(), any())).thenReturn(6L);
            when(queryAuditLogMapper.countUniqueModels(any(), any(), any())).thenReturn(4L);
            when(queryAuditLogMapper.sumTotalRecordsReturned(any(), any(), any())).thenReturn(500L);
        }

        @Test
        @DisplayName("用户统计：计数/最近查询/最活跃小时")
        void userStatisticsAggregated() {
            stubAllLists();
            when(queryAuditLogMapper.countByTenantUserAndTimeRange(any(), any(), any(), any())).thenReturn(50L);
            when(queryAuditLogMapper.countUserSuccessfulQueries(any(), any(), any(), any())).thenReturn(45L);
            when(queryAuditLogMapper.countUserFailedQueries(any(), any(), any(), any())).thenReturn(5L);
            when(queryAuditLogMapper.countUserAccessedModels(any(), any(), any(), any())).thenReturn(3L);
            when(queryAuditLogMapper.getUserLastQueryTime(any(), any(), any(), any()))
                    .thenReturn(Instant.parse("2026-02-01T10:00:00Z"));
            when(queryAuditLogMapper.getUserMostActiveHour(any(), any(), any(), any())).thenReturn(14);
            QueryAuditLog recent = new QueryAuditLog();
            recent.setId(9L);
            when(queryAuditLogMapper.findByUserId(any(), any(), any(), any())).thenReturn(List.of(recent));

            var stats = service.getUserQueryStatistics(7L, 42L, Instant.EPOCH, Instant.now());

            assertThat(stats.getTotalQueries()).isEqualTo(50);
            assertThat(stats.getSuccessRate()).isEqualTo(90.0);
            assertThat(stats.getAccessedModelCount()).isEqualTo(3);
            assertThat(stats.getMostActiveHour()).isEqualTo(14);
            assertThat(stats.getRecentQueries()).hasSize(1);
        }

        private QueryAuditStatisticsRequest reportRequest(String type) {
            QueryAuditStatisticsRequest request = new QueryAuditStatisticsRequest();
            request.setTenantId(42L);
            request.setSlowQueryThreshold(2500);
            return request;
        }

        @Test
        @DisplayName("报告最小生成：默认标题与配置元数据")
        void reportMinimalDefaults() {
            stubAllLists();
            stubCoreCounters();
            com.auraboot.framework.meta.dto.QueryAuditReportRequest request =
                    new com.auraboot.framework.meta.dto.QueryAuditReportRequest();
            request.setTenantId(42L);
            request.setReportType("SECURITY");
            request.setReportFormat("json");
            request.setDetailLevel("standard");

            var report = service.generateAuditReport(request);

            assertThat(report.getReportId()).isNotBlank();
            assertThat(report.getTenantId()).isEqualTo(42L);
            assertThat(report.getTitle()).contains("SECURITY");
            assertThat(report.getConfiguration().getReportFormat()).isEqualTo("json");
            assertThat(report.getConfiguration().getDetailLevel()).isEqualTo("standard");
            assertThat(report.getQueryActivityOverview()).isNotNull();
        }

        @Test
        @DisplayName("报告全开关：七个分析节全部产出")
        void reportFullSections() {
            stubAllLists();
            stubCoreCounters();
            when(queryAuditLogMapper.countByHour(any(), any(), any())).thenReturn(List.of());
            when(queryAuditLogMapper.countByDate(any(), any(), any())).thenReturn(List.of());
            com.auraboot.framework.meta.dto.QueryAuditReportRequest request =
                    new com.auraboot.framework.meta.dto.QueryAuditReportRequest();
            request.setTenantId(42L);
            request.setReportType("FULL");
            request.setIncludeExecutiveSummary(true);
            request.setIncludePerformanceAnalysis(true);
            request.setIncludeSecurityAnalysis(true);
            request.setIncludeUserActivityAnalysis(true);
            request.setIncludeModelUsageAnalysis(true);
            request.setIncludeRecommendations(true);

            var report = service.generateAuditReport(request);

            assertThat(report.getExecutiveSummary()).isNotNull();
            assertThat(report.getPerformanceAnalysis()).isNotNull();
            assertThat(report.getSecurityAnalysis()).isNotNull();
            assertThat(report.getUserActivityAnalysis()).isNotNull();
            assertThat(report.getModelUsageAnalysis()).isNotNull();
            assertThat(report.getRecommendations()).isNotNull();
        }

        @Test
        @DisplayName("导出：批次统计/慢查询计数/分布与完成态")
        void exportAggregatesBatches() {
            stubAllLists();
            var dynamicStub = when(queryAuditLogMapper.countForExport(any(), any(), any(), any(), any()))
                    .thenReturn(2L);
            QueryAuditLog ok = new QueryAuditLog();
            ok.setSuccess(true);
            ok.setExecutionTimeMs(100);
            ok.setQueryType("SELECT_PAGE");
            ok.setModelCode("crm_customer");
            ok.setCreatedAt(Instant.parse("2026-02-01T08:00:00Z"));
            QueryAuditLog slow = new QueryAuditLog();
            slow.setSuccess(true);
            slow.setExecutionTimeMs(6000);
            slow.setQueryType("SELECT_PAGE");
            slow.setModelCode("crm_customer");
            slow.setCreatedAt(Instant.parse("2026-02-01T09:00:00Z"));
            when(queryAuditLogMapper.fetchBatchForExport(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(ok, slow));

            com.auraboot.framework.meta.dto.QueryAuditExportRequest request =
                    new com.auraboot.framework.meta.dto.QueryAuditExportRequest();
            request.setTenantId(42L);
            request.setModelCodes(List.of("crm_customer"));
            request.setSuccessfulQueriesOnly(true);

            var result = service.exportAuditLogs(request);

            assertThat(Boolean.TRUE.equals(result.getSuccess())).isTrue();
            assertThat(result.getStatus()).isEqualTo(com.auraboot.framework.common.constant.StatusConstants.COMPLETED);
            assertThat(result.getProgress()).isEqualTo(100.0);
            assertThat(result.getExportedRecords()).isEqualTo(2);
            assertThat(result.getStatistics().getSlowQueries()).isEqualTo(1);
            assertThat(result.getStatistics().getAverageExecutionTime()).isEqualTo(3050.0);
            assertThat(result.getStatistics().getQueryTypeDistribution()).containsEntry("SELECT_PAGE", 2L);
            assertThat(result.getExecutionInfo().getTotalBatches()).isEqualTo(1);
        }

        @Test
        @DisplayName("导出空集：零记录完成态")
        void exportEmptyCompletes() {
            stubAllLists();
            when(queryAuditLogMapper.countForExport(any(), any(), any(), any(), any())).thenReturn(0L);

            com.auraboot.framework.meta.dto.QueryAuditExportRequest request =
                    new com.auraboot.framework.meta.dto.QueryAuditExportRequest();
            request.setTenantId(42L);

            var result = service.exportAuditLogs(request);

            assertThat(result.getExportedRecords()).isZero();
            assertThat(result.getStatistics().getMinExecutionTime()).isZero();
            assertThat(Boolean.TRUE.equals(result.getSuccess())).isTrue();
        }
    }
}
