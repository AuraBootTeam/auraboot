package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.ExportTaskDTO;
import com.auraboot.framework.meta.dto.NamedQueryDataExportRequest;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Real-stack export lifecycle and artifact tests with per-case query ownership.
 * The real export executor propagates tenant context and processes asynchronously.
 * Teardown waits for submitted workers before deleting only this case's rows.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("ExportTaskService Real-Stack Integration Test")
class ExportTaskServiceIntegrationTest {

    private static final String CODE_PREFIX = "covexp";
    /** Per-run nonce — alnum only, safe as SQL identifier fragment. */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private ExportTaskService exportTaskService;

    @Autowired
    private ExportTaskMapper exportTaskMapper;

    @Autowired
    private NamedQueryMapper namedQueryMapper;

    @Autowired
    private NamedQueryFieldMapper namedQueryFieldMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final long COMPLETION_TIMEOUT_MS = 30_000;
    private final AtomicInteger seq = new AtomicInteger();
    private final List<String> createdQueryCodes = new java.util.ArrayList<>();
    private final List<String> submittedTaskPids = new java.util.ArrayList<>();
    private User testUser;
    private Tenant testTenant;

    /** Returns a per-test unique code. */
    private String uniqueCode(String label) {
        String code = CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
        createdQueryCodes.add(code);
        return code;
    }

    @BeforeEach
    void setUp() {
        String testEmail = "covexp-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covexp-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("ExportTask Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covexp-test.com");
            tenant.setDescription("Test tenant for export-task coverage IT");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            for (String pid : submittedTaskPids) {
                awaitTerminal(pid, COMPLETION_TIMEOUT_MS);
            }
            wipeTenantData();
        } finally {
            MetaContext.clear();
        }
    }

    /** Delete only this case's query rows after all submitted workers reach a terminal state. */
    private void wipeTenantData() {
        Long tid = testTenant.getId();
        for (String code : createdQueryCodes) {
            jdbcTemplate.update("DELETE FROM ab_export_task WHERE tenant_id = ? AND query_code = ?", tid, code);
            jdbcTemplate.update("DELETE FROM ab_named_query_field WHERE tenant_id = ? AND query_code = ?", tid, code);
            jdbcTemplate.update("DELETE FROM ab_named_query WHERE tenant_id = ? AND code = ?", tid, code);
        }
    }

    // ==================== Helpers ====================

    /**
     * Insert a minimal executable NamedQuery (status=DRAFT which is executable)
     * pointing at ab_named_query itself as the data source.
     */
    private NamedQuery insertNamedQuery(String code) {
        NamedQuery nq = new NamedQuery();
        nq.setPid(UniqueIdGenerator.generate());
        nq.setTenantId(testTenant.getId());
        nq.setCode(code);
        nq.setTitle("Export Test Query " + code);
        nq.setDescription("Created by ExportTaskServiceIntegrationTest");
        // Use a simple system table that is always present — delivers a small, deterministic row set
        nq.setFromSql("ab_named_query WHERE tenant_id = #{params.tenantId}");
        nq.setStatus("draft"); // DRAFT is executable per NamedQueryStatus.isExecutable() (DB constraint requires lowercase)
        nq.setCreatedAt(Instant.now());
        nq.setUpdatedAt(Instant.now());
        namedQueryMapper.insert(nq);
        return nq;
    }

    /**
     * Insert two NamedQueryField rows for the given query code so that
     * processExportAsync builds a real column list (instead of SELECT *).
     */
    private void insertFields(String queryCode) {
        NamedQueryField f1 = new NamedQueryField(testTenant.getId(), queryCode, "q_code", "code", "string");
        f1.setSortable(true);
        f1.setSearchable(true);
        namedQueryFieldMapper.insert(f1);

        NamedQueryField f2 = new NamedQueryField(testTenant.getId(), queryCode, "q_title", "title", "string");
        f2.setSortable(false);
        f2.setSearchable(true);
        namedQueryFieldMapper.insert(f2);
    }

    private NamedQueryDataExportRequest exportRequest(DataExportRequest.ExportFormat format) {
        NamedQueryDataExportRequest req = new NamedQueryDataExportRequest();
        req.setFormat(format);
        req.setLimit(100);
        return req;
    }

    private ExportTaskDTO submitTrackedExport(String code, NamedQueryDataExportRequest request,
                                              Long tenantId, Long userId) {
        ExportTaskDTO task = exportTaskService.submitExport(code, request, tenantId, userId);
        submittedTaskPids.add(task.getPid());
        return task;
    }

    /** A non-terminal timeout fails and preserves rows for diagnosis instead of racing the worker. */
    private ExportTaskDTO awaitTerminal(String pid, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ExportTaskDTO dto;
        do {
            dto = exportTaskService.getTaskStatus(pid);
            if (ExportTask.STATUS_COMPLETED.equals(dto.getStatus())
                    || ExportTask.STATUS_FAILED.equals(dto.getStatus())
                    || ExportTask.STATUS_EXPIRED.equals(dto.getStatus())) {
                return dto;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return fail("Export did not reach a terminal state: pid=" + pid
                + ", status=" + dto.getStatus() + ", error=" + dto.getErrorMessage());
    }

    // ==================== submitExport (happy paths) ====================

    @Test
    @DisplayName("submitExport creates and processes an Excel export (waits for async completion)")
    void submitExport_excel_happyPath() throws Exception {
        String code = uniqueCode("excel");
        insertNamedQuery(code);
        insertFields(code);

        ExportTaskDTO initial = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.EXCEL),
                testTenant.getId(), testUser.getId());

        assertNotNull(initial.getPid(), "pid must be set");
        assertEquals(code, initial.getQueryCode());

        // processExportAsync runs on the exportTaskExecutor thread pool — poll until terminal
        ExportTaskDTO dto = awaitTerminal(initial.getPid(), COMPLETION_TIMEOUT_MS);

        assertEquals(ExportTask.STATUS_COMPLETED, dto.getStatus(),
                "status must be COMPLETED after async processing: " + dto.getErrorMessage());
        assertEquals(100, dto.getProgress());
        assertNotNull(dto.getFileSize(), "file size must be set");
        assertTrue(dto.getFileSize() > 0, "file must be non-empty");
        assertNotNull(dto.getDownloadUrl(), "completed task must have download URL");
        assertTrue(dto.getDownloadUrl().contains(dto.getPid()));
        ExportTaskService.ExportArtifactDownload artifact = exportTaskService.openArtifact(dto.getPid());
        assertEquals(".xlsx", artifact.extension());
        assertTrue(artifact.size() > 0);
        try (java.io.InputStream content = artifact.content();
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(content)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            assertEquals("q_code", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("q_title", sheet.getRow(0).getCell(1).getStringCellValue());
            boolean found = false;
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                if (row.getRowNum() > 0 && row.getCell(0) != null
                        && code.equals(row.getCell(0).getStringCellValue())) {
                    assertEquals("Export Test Query " + code, row.getCell(1).getStringCellValue());
                    found = true;
                }
            }
            assertTrue(found, "the downloaded workbook must contain this test's exact query row");
        }
    }

    @Test
    @DisplayName("submitExport creates and processes a CSV export (waits for async completion)")
    void submitExport_csv_happyPath() throws InterruptedException {
        String code = uniqueCode("csv");
        insertNamedQuery(code);
        insertFields(code);

        ExportTaskDTO initial = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.CSV),
                testTenant.getId(), testUser.getId());

        ExportTaskDTO dto = awaitTerminal(initial.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(ExportTask.STATUS_COMPLETED, dto.getStatus(), dto.getErrorMessage());
        assertEquals("CSV", dto.getFormat());
        assertTrue(dto.getFileSize() > 0);
    }

    @Test
    @DisplayName("CSV export fills camelCase field values (declared case survives SQL aliasing)")
    void submitExport_csv_preservesCamelCaseFieldValues() throws Exception {
        String code = uniqueCode("camelcsv");
        insertNamedQuery(code);
        NamedQueryField f1 = new NamedQueryField(testTenant.getId(), code, "rowCode", "code", "string");
        f1.setSortable(true);
        namedQueryFieldMapper.insert(f1);
        NamedQueryField f2 = new NamedQueryField(testTenant.getId(), code, "rowTitle", "title", "string");
        f2.setSortable(false);
        namedQueryFieldMapper.insert(f2);

        ExportTaskDTO initial = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.CSV),
                testTenant.getId(), testUser.getId());
        ExportTaskDTO dto = awaitTerminal(initial.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(ExportTask.STATUS_COMPLETED, dto.getStatus(), dto.getErrorMessage());

        ExportTaskService.ExportArtifactDownload artifact = exportTaskService.openArtifact(dto.getPid());
        assertNotNull(artifact, "completed export must have a file");
        String csv;
        try (java.io.InputStream content = artifact.content()) {
            // exportAsCsv writes with the platform default charset — read symmetrically
            csv = new String(content.readAllBytes());
        }
        String[] lines = csv.lines().toArray(String[]::new);
        assertTrue(lines.length >= 2, "header plus at least one data row expected: " + csv);
        assertEquals("rowCode,rowTitle", lines[0], "header must use declared field codes");
        // The exported query is the row just created above, so its own code must
        // appear as a VALUE — folded lowercase keys would make every cell empty.
        String dataRow = lines[1];
        assertTrue(dataRow.contains(code),
                "camelCase field lookup must resolve values, got: " + dataRow);
    }

    @Test
    @DisplayName("submitExport creates and processes a JSON export (waits for async completion)")
    void submitExport_json_happyPath() throws InterruptedException {
        String code = uniqueCode("json");
        insertNamedQuery(code);
        insertFields(code);

        ExportTaskDTO initial = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.JSON),
                testTenant.getId(), testUser.getId());

        ExportTaskDTO dto = awaitTerminal(initial.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(ExportTask.STATUS_COMPLETED, dto.getStatus(), dto.getErrorMessage());
        assertEquals("JSON", dto.getFormat());
        assertTrue(dto.getFileSize() > 0);
    }

    @Test
    @DisplayName("submitExport with no fields defined falls back to SELECT * and completes")
    void submitExport_noFields_selectStar() throws InterruptedException {
        String code = uniqueCode("nofields");
        insertNamedQuery(code);
        // intentionally insert no NamedQueryFields → falls into the SELECT * branch

        ExportTaskDTO initial = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.CSV),
                testTenant.getId(), testUser.getId());

        ExportTaskDTO dto = awaitTerminal(initial.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(ExportTask.STATUS_COMPLETED, dto.getStatus(), dto.getErrorMessage());
    }

    @Test
    @DisplayName("submitExport throws MetaServiceException when query code is unknown")
    void submitExport_unknownQueryCode_throws() {
        assertThrows(MetaServiceException.class, () ->
                submitTrackedExport(
                        "no-such-query-code-xyz",
                        exportRequest(DataExportRequest.ExportFormat.EXCEL),
                        testTenant.getId(), testUser.getId()));
    }

    @Test
    @DisplayName("processExportAsync marks task FAILED when NamedQuery is missing")
    void processExportAsync_queryMissing_taskFails() throws InterruptedException {
        // Seed a task directly with a query code that has NO matching NamedQuery row in the DB.
        // Then call processExportAsync directly (which dispatches to the thread pool).
        String code = uniqueCode("deleted");

        ExportTask task = new ExportTask();
        task.setPid(UniqueIdGenerator.generate());
        task.setTenantId(testTenant.getId());
        task.setQueryCode(code);
        task.setStatus(ExportTask.STATUS_PENDING);
        task.setProgress(0);
        task.setProcessedRows(0L);
        task.setFormat("excel");
        task.setCreatedBy(testUser.getId());
        task.setCreatedAt(Instant.now());
        task.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        var metadata = objectMapper.createObjectNode();
        metadata.set("request", objectMapper.valueToTree(
                exportRequest(DataExportRequest.ExportFormat.EXCEL)));
        task.setRequestParams(metadata);
        exportTaskMapper.insert(task);
        submittedTaskPids.add(task.getPid());

        // Call processExportAsync directly — it will be dispatched to the thread pool
        exportTaskService.processExportAsync(task.getId(), testTenant.getId());

        // Poll until terminal
        ExportTaskDTO dto = awaitTerminal(task.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(ExportTask.STATUS_FAILED, dto.getStatus());
        assertNotNull(dto.getErrorMessage());
        assertTrue(dto.getErrorMessage().contains("Named query not found"),
                "unexpected export failure: " + dto.getErrorMessage());
    }

    // ==================== getTaskStatus ====================

    @Test
    @DisplayName("getTaskStatus returns the correct DTO for a known pid")
    void getTaskStatus_happy() throws InterruptedException {
        String code = uniqueCode("status");
        insertNamedQuery(code);
        insertFields(code);

        ExportTaskDTO submitted = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.EXCEL),
                testTenant.getId(), testUser.getId());

        ExportTaskDTO fetched = awaitTerminal(submitted.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(submitted.getPid(), fetched.getPid());
        assertEquals(ExportTask.STATUS_COMPLETED, fetched.getStatus(), fetched.getErrorMessage());
        assertNotNull(fetched.getCreatedAt());
        assertNotNull(fetched.getCompletedAt());
        assertNotNull(fetched.getExpiresAt());
    }

    @Test
    @DisplayName("getTaskStatus throws MetaServiceException for an unknown pid")
    void getTaskStatus_notFound_throws() {
        assertThrows(MetaServiceException.class,
                () -> exportTaskService.getTaskStatus("pid-does-not-exist-abc"));
    }

    // ==================== getFileKey ====================

    @Test
    @DisplayName("getFileKey returns the private storage key for a completed task")
    void getFileKey_completed() throws InterruptedException {
        String code = uniqueCode("filekey");
        insertNamedQuery(code);
        insertFields(code);

        ExportTaskDTO initial = submitTrackedExport(
                code, exportRequest(DataExportRequest.ExportFormat.EXCEL),
                testTenant.getId(), testUser.getId());

        // Wait for processing to complete before reading the file key
        ExportTaskDTO completed = awaitTerminal(initial.getPid(), COMPLETION_TIMEOUT_MS);
        assertEquals(ExportTask.STATUS_COMPLETED, completed.getStatus(), completed.getErrorMessage());

        String fileKey = exportTaskService.getFileKey(initial.getPid());
        assertNotNull(fileKey, "completed task must have a file key");
        assertTrue(fileKey.endsWith(".xlsx") || fileKey.contains(code));
    }

    @Test
    @DisplayName("getFileKey returns null for an unknown pid")
    void getFileKey_unknown_returnsNull() {
        String result = exportTaskService.getFileKey("totally-unknown-pid-xyz");
        assertNull(result);
    }

    // ==================== getRecentTasks ====================

    @Test
    @DisplayName("getRecentTasks returns tasks ordered by creation time, limited by count")
    void getRecentTasks_listsByQueryCode() {
        // getRecentTasks reads from the DB directly — no need to wait for async completion.
        // Two tasks will be in pending/running/completed state — all should be listed.
        String code = uniqueCode("recent");
        insertNamedQuery(code);
        insertFields(code);

        // Submit two tasks for the same query
        submitTrackedExport(code, exportRequest(DataExportRequest.ExportFormat.EXCEL),
                testTenant.getId(), testUser.getId());
        submitTrackedExport(code, exportRequest(DataExportRequest.ExportFormat.CSV),
                testTenant.getId(), testUser.getId());

        List<ExportTaskDTO> tasks = exportTaskService.getRecentTasks(code, 10);
        assertEquals(2, tasks.size());

        // All tasks should be for the same query code
        tasks.forEach(t -> assertEquals(code, t.getQueryCode()));
    }

    @Test
    @DisplayName("getRecentTasks respects the limit parameter")
    void getRecentTasks_respectsLimit() {
        String code = uniqueCode("limit");
        insertNamedQuery(code);
        insertFields(code);

        for (int i = 0; i < 3; i++) {
            submitTrackedExport(code, exportRequest(DataExportRequest.ExportFormat.CSV),
                    testTenant.getId(), testUser.getId());
        }

        List<ExportTaskDTO> tasks = exportTaskService.getRecentTasks(code, 2);
        assertEquals(2, tasks.size());
    }

    @Test
    @DisplayName("getRecentTasks returns empty list for unknown query code")
    void getRecentTasks_unknownCode_empty() {
        String ownedCode = uniqueCode("cleanup_scope");
        insertNamedQuery(ownedCode);
        String sentinelPid = UniqueIdGenerator.generate();
        jdbcTemplate.update("INSERT INTO ab_export_task "
                + "(pid, tenant_id, query_code, status, progress, processed_rows, format, created_by, created_at, expires_at) "
                + "VALUES (?, ?, ?, 'pending', 0, 0, 'excel', ?, now(), now() + interval '23 hours')",
                sentinelPid, testTenant.getId(), "foreign_" + RUN, testUser.getId());
        try {
            List<ExportTaskDTO> tasks = exportTaskService.getRecentTasks("no-such-query-abc", 10);
            assertNotNull(tasks);
            assertTrue(tasks.isEmpty());
            wipeTenantData();
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ab_named_query WHERE tenant_id = ? AND code = ?",
                    Integer.class, testTenant.getId(), ownedCode));
            assertNotNull(exportTaskMapper.findByPid(sentinelPid), "cleanup must preserve unrelated query rows");
        } finally {
            jdbcTemplate.update("DELETE FROM ab_export_task WHERE pid = ? AND tenant_id = ?",
                    sentinelPid, testTenant.getId());
        }
    }

    // ==================== cleanupExpiredTasks ====================

    @Test
    @DisplayName("cleanupExpiredTasks marks expired completed tasks as expired and attempts file deletion")
    void cleanupExpiredTasks_marksExpiredRows() {
        String code = uniqueCode("cleanup");
        insertNamedQuery(code);

        // Seed an expired completed task directly — bypassing the service so we control expires_at
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_export_task " +
                "(pid, tenant_id, query_code, status, progress, processed_rows, format, created_by, created_at, completed_at, expires_at) " +
                "VALUES (?, ?, ?, 'completed', 100, 5, 'excel', ?, now(), now(), now() - interval '1 hour')",
                pid, testTenant.getId(), code, testUser.getId());

        // Also seed a pending task that has NOT expired — it must NOT be changed
        String pendingPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_export_task " +
                "(pid, tenant_id, query_code, status, progress, processed_rows, format, created_by, created_at, expires_at) " +
                "VALUES (?, ?, ?, 'pending', 0, 0, 'excel', ?, now(), now() + interval '23 hours')",
                pendingPid, testTenant.getId(), code, testUser.getId());

        exportTaskService.cleanupExpiredTasks();

        // The expired row must now have status=expired
        ExportTask cleaned = exportTaskMapper.findByPid(pid);
        assertNotNull(cleaned, "seeded row must still exist (status updated, not deleted)");
        assertEquals(ExportTask.STATUS_EXPIRED, cleaned.getStatus());

        // The pending task must be unchanged
        ExportTask pending = exportTaskMapper.findByPid(pendingPid);
        assertEquals(ExportTask.STATUS_PENDING, pending.getStatus());
    }

    @Test
    @DisplayName("cleanupExpiredTasks is a no-op when there are no expired tasks")
    void cleanupExpiredTasks_noExpired_noop() {
        // Must not throw; idempotent when no data matches
        assertDoesNotThrow(() -> exportTaskService.cleanupExpiredTasks());
    }

    // ==================== processExportAsync (direct path — null task ID) ====================

    @Test
    @DisplayName("processExportAsync returns silently when task ID is unknown (null guard)")
    void processExportAsync_unknownTaskId_returnsGracefully() {
        // selectById with a non-existent ID returns null; service must return without throwing
        assertDoesNotThrow(() ->
                exportTaskService.processExportAsync(-9999999L, testTenant.getId()));
    }
}
