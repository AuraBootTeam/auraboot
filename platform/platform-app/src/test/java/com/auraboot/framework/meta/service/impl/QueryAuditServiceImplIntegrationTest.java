package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.QueryAuditLog;
import com.auraboot.framework.meta.mapper.QueryAuditLogMapper;
import com.auraboot.framework.meta.service.QueryAuditService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack integration test for {@link QueryAuditServiceImpl}.
 *
 * <p>Part of OSS coverage initiative (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}).
 * Lifts the class from ~8.5% line coverage to ≥70% by exercising the
 * real service against the real shared database (no mocked mappers/bridges,
 * per AGENTS.md §2.2 seam discipline).
 *
 * <p>Coverage strategy:
 * <ul>
 *   <li>Async methods (logQueryExecution / logQueryError / logPermissionCheck /
 *       logSecurityValidation): called via the autowired proxy and polled until the
 *       row appears (up to 5s, 100ms sleep) — ThreadLocals are intentionally absent
 *       from async threads so only DTO-derived fields are asserted.</li>
 *   <li>Synchronous query/stats/anomaly/config/cleanup/archive/export/report methods:
 *       rows seeded via the mapper directly as fixtures, then the SUT method is exercised
 *       and the result is asserted.</li>
 * </ul>
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres :5432 {@code aura_boot}).
 * All rows are hard-deleted in {@link #tearDown()} to keep the shared DB clean.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("QueryAuditServiceImpl Real-Stack Integration Test")
class QueryAuditServiceImplIntegrationTest {

    private static final String CODE_PREFIX = "covqaudit";
    /** Stable per-run nonce — alnum only, LIKE-safe. */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private QueryAuditService queryAuditService;
    @Autowired
    private QueryAuditLogMapper queryAuditLogMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();
    private User testUser;
    private Tenant testTenant;

    // ==================== Lifecycle ====================

    @BeforeEach
    void setUp() {
        String testEmail = "covqaudit-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covqaudit-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant t = new Tenant();
            t.setPid(UniqueIdGenerator.generate());
            t.setName(testTenantName);
            t.setDisplayName("QueryAudit Coverage Test Tenant");
            t.setStatus("active");
            t.setContactEmail("admin@covqaudit-test.com");
            t.setDescription("Test tenant for query-audit domain coverage IT");
            t.setDeletedFlag(false);
            t.setCreatedAt(Instant.now());
            t.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(t);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(
                testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() {
        try {
            jdbcTemplate.update(
                    "DELETE FROM ab_query_audit_log WHERE tenant_id = ?",
                    testTenant.getId());
        } catch (Exception e) {
            log.warn("query-audit cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    // ==================== Helpers ====================

    private String modelCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    /**
     * Seed a minimal valid QueryAuditLog row via the mapper (fixture setup).
     * Only sets non-jsonb columns + sets conditions to '{}' (handled by the TypeHandler).
     */
    private QueryAuditLog seedLog(String modelCode, boolean success, int costMs) {
        QueryAuditLog log = new QueryAuditLog();
        log.setTenantId(testTenant.getId());
        log.setUserId(testUser.getId());
        log.setQueryCode(modelCode);
        log.setModelCode(modelCode);
        log.setQueryType(QueryType.SELECT_ALL.name());
        log.setSuccess(success);
        log.setRejected(false);
        log.setConditions("{}");
        log.setCostMs(costMs);
        log.setExecutionTimeMs(costMs);
        log.setResultCount(success ? 10 : 0);
        log.setCreatedAt(Instant.now());
        queryAuditLogMapper.insert(log);
        return log;
    }

    /**
     * Seed a minimal SecureQueryRequest for async log methods.
     */
    private SecureQueryRequest secureRequest(String modelCode) {
        SecureQueryRequest req = new SecureQueryRequest();
        req.setModelCode(modelCode);
        req.setUserId(testUser.getId());
        req.setTenantId(testTenant.getId());
        req.setQueryType(QueryType.SELECT_ALL);
        req.setQueryId("qid-" + RUN + "-" + seq.incrementAndGet());
        return req;
    }

    /**
     * Poll the DB until at least one row with the given queryCode appears (or times out).
     * The async executor runs on a background thread, so we must not assert synchronously.
     */
    private boolean awaitLogRow(String modelCode, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ab_query_audit_log WHERE tenant_id = ? AND model_code = ?",
                    Integer.class, testTenant.getId(), modelCode);
            if (count != null && count > 0) return true;
            Thread.sleep(100);
        }
        return false;
    }

    // ==================== Async: logQueryExecution ====================

    @Test
    @DisplayName("logQueryExecution inserts a success row (async, polled)")
    void logQueryExecution_insertsRow() throws InterruptedException {
        String model = modelCode("exec");
        SecureQueryRequest req = secureRequest(model);

        queryAuditService.logQueryExecution(req, List.of("row1", "row2"), 42L);

        assertTrue(awaitLogRow(model, 5000), "async insert did not appear within 5s");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_query_audit_log WHERE tenant_id = ? AND model_code = ? AND success = true",
                Integer.class, testTenant.getId(), model);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("logQueryExecution with null result still inserts")
    void logQueryExecution_nullResult() throws InterruptedException {
        String model = modelCode("exec_null");
        SecureQueryRequest req = secureRequest(model);

        queryAuditService.logQueryExecution(req, null, 10L);

        assertTrue(awaitLogRow(model, 5000));
    }

    // ==================== Async: logQueryError ====================

    @Test
    @DisplayName("logQueryError inserts a failed row (async, polled)")
    void logQueryError_insertsFailedRow() throws InterruptedException {
        String model = modelCode("err");
        SecureQueryRequest req = secureRequest(model);

        queryAuditService.logQueryError(req, new RuntimeException("test error"), 99L);

        assertTrue(awaitLogRow(model, 5000));

        Integer failCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_query_audit_log WHERE tenant_id = ? AND model_code = ? AND success = false",
                Integer.class, testTenant.getId(), model);
        assertEquals(1, failCount);
    }

    // ==================== Async: logPermissionCheck ====================

    @Test
    @DisplayName("logPermissionCheck inserts row only when permission is denied")
    void logPermissionCheck_onlyOnDeny() throws InterruptedException {
        String modelDenied = modelCode("perm_denied");
        String modelAllowed = modelCode("perm_allowed");

        // Permission denied -> should insert
        SecureQueryRequest deniedReq = secureRequest(modelDenied);
        QueryAccessCheckResult denied = QueryAccessCheckResult.builder()
                .hasAccess(false).denyReason("no-access").build();
        queryAuditService.logPermissionCheck(deniedReq, denied);
        assertTrue(awaitLogRow(modelDenied, 5000), "denied check should insert a row");

        // Permission allowed -> should NOT insert
        SecureQueryRequest allowedReq = secureRequest(modelAllowed);
        QueryAccessCheckResult allowed = QueryAccessCheckResult.builder()
                .hasAccess(true).build();
        queryAuditService.logPermissionCheck(allowedReq, allowed);
        Thread.sleep(300); // brief wait to confirm no insert
        Integer allowedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_query_audit_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), modelAllowed);
        assertEquals(0, allowedCount, "allowed permission check must NOT insert a row");
    }

    // ==================== Async: logSecurityValidation ====================

    @Test
    @DisplayName("logSecurityValidation inserts row when validation fails")
    void logSecurityValidation_onFailure() throws InterruptedException {
        String model = modelCode("secval");
        SecureQueryRequest req = secureRequest(model);

        QuerySecurityValidationResult secResult = new QuerySecurityValidationResult();
        secResult.setValid(false);
        QuerySecurityValidationResult.SecurityIssue issue = new QuerySecurityValidationResult.SecurityIssue();
        issue.setType("SQL_INJECTION_ATTEMPT");
        issue.setDescription("Suspicious pattern detected");
        secResult.setSecurityIssues(List.of(issue));

        queryAuditService.logSecurityValidation(req, secResult);

        assertTrue(awaitLogRow(model, 5000));
    }

    @Test
    @DisplayName("logSecurityValidation does NOT insert row when validation passes with no issues")
    void logSecurityValidation_onSuccess_noInsert() throws InterruptedException {
        String model = modelCode("secval_ok");
        SecureQueryRequest req = secureRequest(model);

        QuerySecurityValidationResult secResult = new QuerySecurityValidationResult();
        secResult.setValid(true);
        secResult.setSecurityIssues(null);

        queryAuditService.logSecurityValidation(req, secResult);
        Thread.sleep(300);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_query_audit_log WHERE tenant_id = ? AND model_code = ?",
                Integer.class, testTenant.getId(), model);
        assertEquals(0, count);
    }

    // ==================== queryAuditLogs (synchronous) ====================

    @Test
    @DisplayName("queryAuditLogs paginates and filters by tenantId")
    void queryAuditLogs_paginates() {
        String model = modelCode("qal");
        seedLog(model, true, 50);
        seedLog(model, false, 100);

        QueryAuditLogQueryRequest req = new QueryAuditLogQueryRequest();
        req.setTenantId(testTenant.getId());
        req.setPage(1);
        req.setSize(10);

        PageResult<QueryAuditLogDTO> result = queryAuditService.queryAuditLogs(req);
        assertNotNull(result);
        assertTrue(result.getTotal() >= 2);
        assertFalse(result.getRecords().isEmpty());
    }

    @Test
    @DisplayName("queryAuditLogs filters by modelCode and success flag")
    void queryAuditLogs_filtersByModelAndSuccess() {
        String model = modelCode("filter");
        seedLog(model, true, 50);
        seedLog(model, false, 80);

        QueryAuditLogQueryRequest req = new QueryAuditLogQueryRequest();
        req.setTenantId(testTenant.getId());
        req.setModelCode(model);
        req.setSuccess(true);
        req.setPage(1);
        req.setSize(10);

        PageResult<QueryAuditLogDTO> result = queryAuditService.queryAuditLogs(req);
        assertEquals(1L, result.getTotal());
        assertTrue(result.getRecords().stream().allMatch(r -> Boolean.TRUE.equals(r.getSuccess())));
    }

    // ==================== queryAuditLogsByUser / ByModel / Failed ====================

    @Test
    @DisplayName("queryAuditLogsByUser returns logs for the specified user")
    void queryAuditLogsByUser_returnsLogs() {
        String model = modelCode("byuser");
        seedLog(model, true, 30);

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        List<QueryAuditLogDTO> logs = queryAuditService.queryAuditLogsByUser(
                testUser.getId(), testTenant.getId(), start, end);
        assertNotNull(logs);
        assertTrue(logs.stream().anyMatch(l -> model.equals(l.getModelCode())));
    }

    @Test
    @DisplayName("queryAuditLogsByModel returns logs for the specified model")
    void queryAuditLogsByModel_returnsLogs() {
        String model = modelCode("bymodel");
        seedLog(model, true, 20);

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        List<QueryAuditLogDTO> logs = queryAuditService.queryAuditLogsByModel(
                model, testTenant.getId(), start, end);
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().allMatch(l -> model.equals(l.getModelCode())));
    }

    @Test
    @DisplayName("queryFailedQueries returns only failed logs")
    void queryFailedQueries_returnsOnlyFailed() {
        String model = modelCode("failed");
        seedLog(model, false, 300);

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        List<QueryAuditLogDTO> failed = queryAuditService.queryFailedQueries(
                testTenant.getId(), start, end);
        assertNotNull(failed);
        assertTrue(failed.stream().anyMatch(l -> model.equals(l.getModelCode())));
        assertTrue(failed.stream().allMatch(l -> Boolean.FALSE.equals(l.getSuccess())));
    }

    // ==================== getQueryStatistics ====================

    @Test
    @DisplayName("getQueryStatistics returns non-null stats and counts seeded rows correctly")
    void getQueryStatistics_basic() {
        String model = modelCode("stats");
        seedLog(model, true, 50);
        seedLog(model, true, 80);
        seedLog(model, false, 200);

        QueryAuditStatisticsRequest req = new QueryAuditStatisticsRequest();
        req.setTenantId(testTenant.getId());
        // Use very wide window to include seeded rows
        req.setStartTime(java.time.LocalDateTime.now().minusHours(2));
        req.setEndTime(java.time.LocalDateTime.now().plusHours(1));

        QueryAuditStatistics stats = queryAuditService.getQueryStatistics(req);
        assertNotNull(stats);
        assertTrue(stats.getTotalQueries() >= 3);
        assertTrue(stats.getSuccessfulQueries() >= 2);
        assertTrue(stats.getFailedQueries() >= 1);
        assertNotNull(stats.getSuccessRate());
        assertNotNull(stats.getAverageExecutionTime());
        assertNotNull(stats.getQueryTypeStatistics());
        assertNotNull(stats.getModelStatistics());
    }

    @Test
    @DisplayName("getQueryStatistics with hourly and daily stats enabled")
    void getQueryStatistics_withHourlyAndDaily() {
        String model = modelCode("stats_hd");
        seedLog(model, true, 100);

        QueryAuditStatisticsRequest req = new QueryAuditStatisticsRequest();
        req.setTenantId(testTenant.getId());
        req.setIncludeHourlyStats(true);
        req.setIncludeDailyStats(true);

        QueryAuditStatistics stats = queryAuditService.getQueryStatistics(req);
        assertNotNull(stats);
        // hourly and daily stats may be empty maps but not null
        assertNotNull(stats.getHourlyStatistics());
        assertNotNull(stats.getDailyStatistics());
    }

    // ==================== getUserQueryStatistics ====================

    @Test
    @DisplayName("getUserQueryStatistics returns stats for the specified user")
    void getUserQueryStatistics_basic() {
        String model = modelCode("ustats");
        seedLog(model, true, 60);
        seedLog(model, false, 150);

        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        UserQueryStatistics stats = queryAuditService.getUserQueryStatistics(
                testUser.getId(), testTenant.getId(), start, end);
        assertNotNull(stats);
        assertEquals(testUser.getId(), stats.getUserId());
        assertEquals(testTenant.getId(), stats.getTenantId());
        assertTrue(stats.getTotalQueries() >= 2);
        assertTrue(stats.getSuccessfulQueries() >= 1);
        assertTrue(stats.getFailedQueries() >= 1);
        assertNotNull(stats.getQueryTypeStatistics());
        assertNotNull(stats.getModelStatistics());
    }

    @Test
    @DisplayName("getUserQueryStatistics with null time range uses default 30-day window")
    void getUserQueryStatistics_nullTimeRange() {
        UserQueryStatistics stats = queryAuditService.getUserQueryStatistics(
                testUser.getId(), testTenant.getId(), null, null);
        assertNotNull(stats);
        assertNotNull(stats.getStartTime());
        assertNotNull(stats.getEndTime());
    }

    // ==================== getModelQueryStatistics ====================

    @Test
    @DisplayName("getModelQueryStatistics returns stats for the specified model")
    void getModelQueryStatistics_basic() {
        String model = modelCode("mstats");
        seedLog(model, true, 40);
        seedLog(model, true, 70);

        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        ModelQueryStatistics stats = queryAuditService.getModelQueryStatistics(
                model, testTenant.getId(), start, end);
        assertNotNull(stats);
        assertEquals(model, stats.getModelCode());
        assertEquals(testTenant.getId(), stats.getTenantId());
        assertTrue(stats.getTotalQueries() >= 2);
        assertTrue(stats.getSuccessfulQueries() >= 2);
    }

    @Test
    @DisplayName("getModelQueryStatistics with null time range uses default 30-day window")
    void getModelQueryStatistics_nullTimeRange() {
        String model = modelCode("mstats_null");
        ModelQueryStatistics stats = queryAuditService.getModelQueryStatistics(
                model, testTenant.getId(), null, null);
        assertNotNull(stats);
        assertEquals(0L, stats.getTotalQueries()); // no rows for this fresh model
    }

    // ==================== getQueryPerformanceStatistics ====================

    @Test
    @DisplayName("getQueryPerformanceStatistics returns performance stats")
    void getQueryPerformanceStatistics_basic() {
        String model = modelCode("perfstats");
        seedLog(model, true, 100);
        seedLog(model, true, 200);

        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        QueryPerformanceStatistics stats = queryAuditService.getQueryPerformanceStatistics(
                testTenant.getId(), start, end);
        assertNotNull(stats);
        assertEquals(testTenant.getId(), stats.getTenantId());
        assertNotNull(stats.getAverageExecutionTime());
        assertNotNull(stats.getSlowQueryCount());
        assertNotNull(stats.getExecutionTimeDistribution());
    }

    // ==================== detectAnomalousQueries ====================

    @Test
    @DisplayName("detectAnomalousQueries returns a non-null result with populated metadata")
    void detectAnomalousQueries_emptyData() {
        QueryAnomalyDetectionRequest req = new QueryAnomalyDetectionRequest();
        req.setTenantId(testTenant.getId());
        req.setDetectAbnormalAccess(true);
        req.setDetectDataLeakage(true);

        QueryAnomalyDetectionResult result = queryAuditService.detectAnomalousQueries(req);
        assertNotNull(result);
        assertEquals(testTenant.getId(), result.getTenantId());
        assertNotNull(result.getAnomalies());
        assertNotNull(result.getExecutionInfo());
        assertNotNull(result.getRiskAssessment());
        // With empty data: no anomalies expected
        assertFalse(result.getAnomaliesDetected());
    }

    // ==================== detectFrequentQueries ====================

    @Test
    @DisplayName("detectFrequentQueries returns false below threshold, true above")
    void detectFrequentQueries_thresholdBehavior() {
        // With no seeded rows, should be below any positive threshold
        boolean below = queryAuditService.detectFrequentQueries(
                testUser.getId(), testTenant.getId(), 60, 1000);
        assertFalse(below, "no rows → below threshold");

        // Seed rows then check with threshold=0 (any count > 0 triggers)
        String model = modelCode("freq");
        seedLog(model, true, 10);
        seedLog(model, true, 20);

        boolean above = queryAuditService.detectFrequentQueries(
                testUser.getId(), testTenant.getId(), 60, 0);
        assertTrue(above, "2 rows > threshold 0 → above threshold");
    }

    // ==================== detectSlowQueries ====================

    @Test
    @DisplayName("detectSlowQueries returns logs above threshold, not below")
    void detectSlowQueries_filter() {
        String model = modelCode("slow");
        seedLog(model, true, 100);   // fast
        seedLog(model, true, 8000);  // slow (above default 5000ms threshold)

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        List<QueryAuditLogDTO> slow = queryAuditService.detectSlowQueries(
                testTenant.getId(), 5000L, start, end);
        assertNotNull(slow);
        // The 8000ms row should appear; the 100ms row should not
        assertTrue(slow.stream().anyMatch(l -> model.equals(l.getModelCode())));
        assertTrue(slow.stream()
                .filter(l -> model.equals(l.getModelCode()))
                .allMatch(l -> l.getExecutionTimeMs() != null && l.getExecutionTimeMs() > 5000));
    }

    // ==================== detectSuspiciousQueryPatterns ====================

    @Test
    @DisplayName("detectSuspiciousQueryPatterns returns a list (empty when no anomalies)")
    void detectSuspiciousQueryPatterns_noData() {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        List<QueryAuditLogDTO> suspicious = queryAuditService.detectSuspiciousQueryPatterns(
                testTenant.getId(), start, end);
        assertNotNull(suspicious);
    }

    @Test
    @DisplayName("detectSuspiciousQueryPatterns with null time range uses default 24h window")
    void detectSuspiciousQueryPatterns_nullTimeRange() {
        List<QueryAuditLogDTO> suspicious = queryAuditService.detectSuspiciousQueryPatterns(
                testTenant.getId(), null, null);
        assertNotNull(suspicious);
    }

    // ==================== Audit Configuration ====================

    @Test
    @DisplayName("getAuditConfig returns default config on first call; updateAuditConfig persists the change")
    void auditConfig_getAndUpdate() {
        Long tid = testTenant.getId();

        // First call creates default config in-memory
        QueryAuditConfig cfg = queryAuditService.getAuditConfig(tid);
        assertNotNull(cfg);
        assertEquals(tid, cfg.getTenantId());
        assertTrue(cfg.getAuditEnabled());
        assertEquals(5000, (int) cfg.getSlowQueryThreshold());

        // Update and verify
        cfg.setSlowQueryThreshold(3000);
        cfg.setRetentionDays(60);
        queryAuditService.updateAuditConfig(tid, cfg);

        QueryAuditConfig updated = queryAuditService.getAuditConfig(tid);
        assertEquals(3000, (int) updated.getSlowQueryThreshold());
        assertEquals(60, (int) updated.getRetentionDays());
    }

    @Test
    @DisplayName("updateAuditConfig with null throws IllegalArgumentException")
    void auditConfig_updateNull_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> queryAuditService.updateAuditConfig(testTenant.getId(), null));
    }

    @Test
    @DisplayName("setAuditEnabled toggles the enabled flag")
    void setAuditEnabled_toggle() {
        Long tid = testTenant.getId();
        // ensure initial state
        queryAuditService.setAuditEnabled(tid, true);
        assertTrue(queryAuditService.getAuditConfig(tid).getAuditEnabled());

        queryAuditService.setAuditEnabled(tid, false);
        assertFalse(queryAuditService.getAuditConfig(tid).getAuditEnabled());

        queryAuditService.setAuditEnabled(tid, true);
        assertTrue(queryAuditService.getAuditConfig(tid).getAuditEnabled());
    }

    // ==================== cleanupExpiredAuditLogs ====================

    @Test
    @DisplayName("cleanupExpiredAuditLogs removes rows older than retentionDays")
    void cleanupExpiredAuditLogs_deletesOldRows() {
        // Seed an old row by inserting with backdated created_at
        jdbcTemplate.update(
                "INSERT INTO ab_query_audit_log "
                + "(tenant_id, query_code, model_code, query_type, success, rejected, conditions, created_at) "
                + "VALUES (?, ?, ?, ?, true, false, '{}'::jsonb, NOW() - INTERVAL '100 days')",
                testTenant.getId(),
                CODE_PREFIX + "cleanup_old",
                CODE_PREFIX + "cleanup_old",
                QueryType.SELECT_ALL.name());

        int deleted = queryAuditService.cleanupExpiredAuditLogs(testTenant.getId(), 90);
        assertTrue(deleted >= 1, "should have deleted at least 1 expired row");
    }

    @Test
    @DisplayName("cleanupExpiredAuditLogs returns 0 when no expired rows exist")
    void cleanupExpiredAuditLogs_noExpiredRows() {
        // Seed a fresh row (within retention period)
        String model = modelCode("cleanup_fresh");
        seedLog(model, true, 10);

        int deleted = queryAuditService.cleanupExpiredAuditLogs(testTenant.getId(), 90);
        assertEquals(0, deleted, "recent row should NOT be deleted");
    }

    // ==================== archiveAuditLogs ====================

    @Test
    @DisplayName("archiveAuditLogs returns 0 when no rows before the archive cutoff")
    void archiveAuditLogs_nothingToArchive() {
        String model = modelCode("archive_fresh");
        seedLog(model, true, 10);

        // Archive date far in the past — nothing qualifies
        int archived = queryAuditService.archiveAuditLogs(
                testTenant.getId(), Instant.now().minus(365, ChronoUnit.DAYS));
        assertEquals(0, archived);
    }

    @Test
    @DisplayName("archiveAuditLogs deletes old rows when present")
    void archiveAuditLogs_deletesOldRows() {
        jdbcTemplate.update(
                "INSERT INTO ab_query_audit_log "
                + "(tenant_id, query_code, model_code, query_type, success, rejected, conditions, created_at) "
                + "VALUES (?, ?, ?, ?, true, false, '{}'::jsonb, NOW() - INTERVAL '200 days')",
                testTenant.getId(),
                CODE_PREFIX + "archive_old",
                CODE_PREFIX + "archive_old",
                QueryType.SELECT_ALL.name());

        int archived = queryAuditService.archiveAuditLogs(
                testTenant.getId(), Instant.now().minus(90, ChronoUnit.DAYS));
        assertTrue(archived >= 1);
    }

    @Test
    @DisplayName("archiveAuditLogs with null archiveBeforeDate defaults to 90 days ago")
    void archiveAuditLogs_nullDate_usesDefault() {
        int archived = queryAuditService.archiveAuditLogs(testTenant.getId(), null);
        // With only fresh test rows this should be 0
        assertEquals(0, archived);
    }

    // ==================== generateAuditReport ====================

    @Test
    @DisplayName("generateAuditReport returns a populated report with all sections")
    void generateAuditReport_allSections() {
        String model = modelCode("report");
        seedLog(model, true, 100);
        seedLog(model, false, 200);

        QueryAuditReportRequest req = new QueryAuditReportRequest();
        req.setTenantId(testTenant.getId());
        req.setReportType("weekly");
        req.setTitle("Test Weekly Report");
        req.setPeriodStartTime(java.time.LocalDateTime.now().minusHours(2));
        req.setPeriodEndTime(java.time.LocalDateTime.now().plusHours(1));
        req.setIncludeExecutiveSummary(true);
        req.setIncludePerformanceAnalysis(true);
        req.setIncludeSecurityAnalysis(true);
        req.setIncludeUserActivityAnalysis(true);
        req.setIncludeModelUsageAnalysis(true);
        req.setIncludeRecommendations(true);

        QueryAuditReport report = queryAuditService.generateAuditReport(req);
        assertNotNull(report);
        assertNotNull(report.getReportId());
        assertEquals(testTenant.getId(), report.getTenantId());
        assertEquals("weekly", report.getReportType());
        assertNotNull(report.getQueryActivityOverview());
        assertNotNull(report.getExecutiveSummary());
        assertNotNull(report.getPerformanceAnalysis());
        assertNotNull(report.getSecurityAnalysis());
        assertNotNull(report.getUserActivityAnalysis());
        assertNotNull(report.getModelUsageAnalysis());
        assertNotNull(report.getRecommendations());
        assertFalse(report.getRecommendations().isEmpty(),
                "recommendations list should contain at least one item");
    }

    @Test
    @DisplayName("generateAuditReport with minimal config returns at minimum queryActivityOverview")
    void generateAuditReport_minimal() {
        QueryAuditReportRequest req = new QueryAuditReportRequest();
        req.setTenantId(testTenant.getId());
        req.setReportType("daily");
        req.setIncludeExecutiveSummary(false);
        req.setIncludePerformanceAnalysis(false);
        req.setIncludeSecurityAnalysis(false);
        req.setIncludeUserActivityAnalysis(false);
        req.setIncludeModelUsageAnalysis(false);
        req.setIncludeRecommendations(false);

        QueryAuditReport report = queryAuditService.generateAuditReport(req);
        assertNotNull(report);
        assertNotNull(report.getQueryActivityOverview());
        assertNull(report.getExecutiveSummary());
    }

    // ==================== exportAuditLogs ====================

    @Test
    @DisplayName("exportAuditLogs processes all seeded rows and returns export result")
    void exportAuditLogs_basic() {
        String model = modelCode("export");
        seedLog(model, true, 50);
        seedLog(model, true, 100);
        seedLog(model, false, 300);

        QueryAuditExportRequest req = new QueryAuditExportRequest();
        req.setTenantId(testTenant.getId());
        req.setExportFormat("csv");
        req.setPageSize(10);

        QueryAuditExportResult result = queryAuditService.exportAuditLogs(req);
        assertNotNull(result);
        assertNotNull(result.getExportTaskId());
        assertEquals(testTenant.getId(), result.getTenantId());
        assertTrue(result.getExportedRecords() >= 3);
        assertTrue(result.getSuccess());
        assertNotNull(result.getStatistics());
        assertTrue(result.getStatistics().getTotalQueries() >= 3);
        assertTrue(result.getStatistics().getSuccessfulQueries() >= 2);
        assertTrue(result.getStatistics().getFailedQueries() >= 1);
    }

    @Test
    @DisplayName("exportAuditLogs with successfulQueriesOnly=true excludes failed rows")
    void exportAuditLogs_successfulOnly() {
        String model = modelCode("export_suc");
        seedLog(model, true, 50);
        seedLog(model, false, 200);

        QueryAuditExportRequest req = new QueryAuditExportRequest();
        req.setTenantId(testTenant.getId());
        req.setExportFormat("json");
        req.setSuccessfulQueriesOnly(true);
        req.setPageSize(100);

        QueryAuditExportResult result = queryAuditService.exportAuditLogs(req);
        assertNotNull(result);
        assertEquals(0L, result.getStatistics().getFailedQueries());
        assertTrue(result.getStatistics().getSuccessfulQueries() >= 1);
    }

    @Test
    @DisplayName("exportAuditLogs with empty dataset returns success with zero records")
    void exportAuditLogs_emptyDataset() {
        // Use a very narrow time window with no data
        QueryAuditExportRequest req = new QueryAuditExportRequest();
        req.setTenantId(testTenant.getId());
        req.setExportFormat("csv");
        req.setStartTime(java.time.LocalDateTime.now().minusSeconds(1));
        req.setEndTime(java.time.LocalDateTime.now().minusSeconds(1)); // empty window

        QueryAuditExportResult result = queryAuditService.exportAuditLogs(req);
        assertNotNull(result);
        assertEquals(0L, result.getExportedRecords());
        assertTrue(result.getSuccess());
    }
}
