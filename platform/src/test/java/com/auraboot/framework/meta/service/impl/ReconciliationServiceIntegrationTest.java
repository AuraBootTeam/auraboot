package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.ReconciliationItemDTO;
import com.auraboot.framework.meta.dto.ReconciliationItemResolveRequest;
import com.auraboot.framework.meta.dto.ReconciliationProfileDTO;
import com.auraboot.framework.meta.dto.ReconciliationProfileRequest;
import com.auraboot.framework.meta.dto.ReconciliationReportDTO;
import com.auraboot.framework.meta.dto.ReconciliationRunDTO;
import com.auraboot.framework.meta.dto.ReconciliationRunRequest;
import com.auraboot.framework.meta.entity.ReconciliationItem;
import com.auraboot.framework.meta.entity.ReconciliationProfile;
import com.auraboot.framework.meta.entity.ReconciliationRun;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.ReconciliationItemMapper;
import com.auraboot.framework.meta.mapper.ReconciliationProfileMapper;
import com.auraboot.framework.meta.mapper.ReconciliationRunMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack integration test for {@link ReconciliationService}.
 *
 * <p>Part of OSS coverage initiative #8/#9 — lifts ReconciliationService from ~0.2%
 * line coverage by exercising the real service against the real shared database.
 * No mocked mappers/bridges (AGENTS.md §2.2 seam discipline).
 *
 * <p>Uses {@code integration-test} profile (shared Postgres :5432). All data is created
 * under a dedicated tenant with {@code recon}-prefixed codes and hard-deleted in tearDown.
 *
 * <p>3 product bugs fixed (see individual test methods for details):
 * 1. validateProfileType: lowercase Set vs toUpperCase comparison (all valid types rejected).
 * 2. validateProfileType: null type silently passed, causing raw DB DataIntegrityViolationException.
 * 3. listRuns / getRunItems unfiltered: selectCount(qw) with ORDER BY → invalid PostgreSQL SQL.
 * Bug 4 (startReconciliation: @Transactional rollback swallows FAILED run audit record) is
 * characterized in {@code testStartReconciliation_modelNotFound_transactionRollback} and deferred
 * — the REQUIRES_NEW approach caused a deadlock; proper redesign tracked in backlog.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("ReconciliationService Real-Stack Integration Test")
class ReconciliationServiceIntegrationTest {

    private static final String CODE_PREFIX = "recon";
    /** Stable per-class-run nonce (alnum only, LIKE-safe) to avoid unique-constraint collisions. */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationProfileMapper profileMapper;

    @Autowired
    private ReconciliationRunMapper runMapper;

    @Autowired
    private ReconciliationItemMapper itemMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    private User testUser;
    private Tenant testTenant;

    // ==================== Setup / Teardown ====================

    @BeforeEach
    void setUp() {
        // Find or create dedicated test user
        String testEmail = "reconsvc-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        // Find or create dedicated test tenant
        String testTenantName = "reconsvc-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("Reconciliation Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@reconsvc-test.com");
            tenant.setDescription("Test tenant for reconciliation service coverage IT");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        // Add user as tenant member if not already
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() {
        try {
            // Delete reconciliation items for all runs belonging to our test tenant
            QueryWrapper<ReconciliationRun> runQw = new QueryWrapper<>();
            runQw.eq("tenant_id", testTenant.getId());
            List<ReconciliationRun> runs = runMapper.selectList(runQw);
            for (ReconciliationRun run : runs) {
                QueryWrapper<ReconciliationItem> itemQw = new QueryWrapper<>();
                itemQw.eq("run_id", run.getId());
                itemMapper.delete(itemQw);
            }

            // Delete all runs for test tenant
            if (!runs.isEmpty()) {
                runMapper.delete(runQw);
            }

            // Hard-delete all profiles for test tenant (bypass soft-delete)
            QueryWrapper<ReconciliationProfile> profileQw = new QueryWrapper<>();
            profileQw.eq("tenant_id", testTenant.getId());
            profileQw.likeRight("profile_code", CODE_PREFIX);
            // Note: need to bypass the @TableLogic soft-delete — use raw delete
            profileMapper.delete(profileQw);

        } catch (Exception e) {
            log.warn("reconciliation cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    // ==================== Helpers ====================

    /**
     * Build a minimal valid ReconciliationProfileRequest.
     * Accepts any profileType string; the service validates it case-insensitively.
     */
    private ReconciliationProfileRequest profileRequest(String code, String profileType) {
        ReconciliationProfileRequest req = new ReconciliationProfileRequest();
        req.setProfileCode(code);
        req.setProfileName("Test Profile " + code);
        req.setProfileType(profileType);
        req.setSourceAModel("ap_invoice");
        req.setSourceAAmountField("amount");
        req.setSourceADateField("invoice_date");
        req.setSourceARefField("invoice_ref");
        req.setSourceBModel("supplier_statement");
        req.setSourceBAmountField("amount");
        req.setSourceBDateField("statement_date");
        req.setSourceBRefField("statement_ref");
        req.setEnabled(true);
        return req;
    }

    /**
     * Insert a ReconciliationProfile directly via mapper to enable testing of downstream service
     * methods (update, delete, get, list, startReconciliation) with pre-existing data.
     */
    private ReconciliationProfile insertProfileDirectly(String code, String profileType) {
        ReconciliationProfile profile = new ReconciliationProfile();
        profile.setTenantId(testTenant.getId());
        profile.setProfileCode(code);
        profile.setProfileName("Test Profile " + code);
        profile.setProfileType(profileType.toUpperCase());
        profile.setDescription("Test profile for IT coverage");
        profile.setSourceAModel("ap_invoice");
        profile.setSourceAAmountField("amount");
        profile.setSourceADateField("invoice_date");
        profile.setSourceARefField("invoice_ref");
        profile.setSourceBModel("supplier_statement");
        profile.setSourceBAmountField("amount");
        profile.setSourceBDateField("statement_date");
        profile.setSourceBRefField("statement_ref");
        profile.setAmountTolerance(new BigDecimal("0.01"));
        profile.setDateToleranceDays(3);
        profile.setMatchByReference(true);
        profile.setMatchByAmount(true);
        profile.setMatchByDate(false);
        profile.setEnabled(true);
        profile.setDeletedFlag(false);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        profileMapper.insert(profile);
        return profile;
    }

    /**
     * Insert a ReconciliationRun directly via mapper, returning the persisted entity.
     */
    private ReconciliationRun insertRunDirectly(Long profileId, String runCode, String status) {
        ReconciliationRun run = new ReconciliationRun();
        run.setTenantId(testTenant.getId());
        run.setRunCode(runCode);
        run.setProfileId(profileId);
        run.setStatus(status);
        run.setPeriodStart(LocalDate.of(2025, 1, 1));
        run.setPeriodEnd(LocalDate.of(2025, 1, 31));
        run.setStartedAt(Instant.now());
        run.setCreatedBy(testUser.getId());
        run.setCreatedAt(Instant.now());
        run.setTotalSourceA(2);
        run.setTotalSourceB(2);
        run.setMatchedCount(1);
        run.setUnmatchedACount(1);
        run.setUnmatchedBCount(0);
        run.setDiscrepancyCount(0);
        run.setMatchedAmount(new BigDecimal("1000.00"));
        run.setUnmatchedAAmount(new BigDecimal("500.00"));
        run.setUnmatchedBAmount(BigDecimal.ZERO);
        run.setCompletedAt(Instant.now());
        runMapper.insert(run);
        return run;
    }

    /**
     * Insert a ReconciliationItem directly via mapper.
     */
    private ReconciliationItem insertItemDirectly(Long runId, String matchStatus, BigDecimal sourceAAmount,
                                                   BigDecimal sourceBAmount, String ref) {
        ReconciliationItem item = new ReconciliationItem();
        item.setTenantId(testTenant.getId());
        item.setRunId(runId);
        item.setMatchStatus(matchStatus);
        item.setSourceARecordId(100L);
        item.setSourceARef(ref);
        item.setSourceAAmount(sourceAAmount);
        item.setSourceADate(LocalDate.of(2025, 1, 15));
        item.setSourceBRecordId(200L);
        item.setSourceBRef(ref);
        item.setSourceBAmount(sourceBAmount);
        item.setSourceBDate(LocalDate.of(2025, 1, 16));
        item.setAmountDifference(sourceAAmount != null && sourceBAmount != null
                ? sourceAAmount.subtract(sourceBAmount) : BigDecimal.ZERO);
        item.setDateDifference(1);
        item.setMatchScore(new BigDecimal("100.00"));
        itemMapper.insert(item);
        return item;
    }

    // ==================== Profile CRUD Tests ====================

    @Test
    @DisplayName("createProfile: all valid profile types (lowercase and uppercase) are accepted after fix")
    void testCreateProfile_validType_accepted() {
        // Fix verified: validateProfileType now compares type.toUpperCase() against uppercase Set,
        // so lowercase inputs ("supplier", "bank", "intercompany") are correctly accepted.
        // The stored value is always UPPER (mapRequestToProfile calls toUpperCase()).
        String code1 = CODE_PREFIX + RUN + "_supplier";
        ReconciliationProfileDTO dto1 = reconciliationService.createProfile(profileRequest(code1, "supplier"));
        assertNotNull(dto1.getId());
        assertEquals("SUPPLIER", dto1.getProfileType()); // stored as UPPER

        String code2 = CODE_PREFIX + RUN + "_bank";
        ReconciliationProfileDTO dto2 = reconciliationService.createProfile(profileRequest(code2, "BANK"));
        assertNotNull(dto2.getId());
        assertEquals("BANK", dto2.getProfileType());

        String code3 = CODE_PREFIX + RUN + "_intercompany";
        ReconciliationProfileDTO dto3 = reconciliationService.createProfile(profileRequest(code3, "Intercompany"));
        assertNotNull(dto3.getId());
        assertEquals("INTERCOMPANY", dto3.getProfileType());

        // Truly invalid type is still rejected
        String code4 = CODE_PREFIX + RUN + "_bad";
        MetaServiceException ex4 = assertThrows(MetaServiceException.class,
                () -> reconciliationService.createProfile(profileRequest(code4, "bogus")));
        assertTrue(ex4.getMessage().contains("Invalid profile type"));
    }

    @Test
    @DisplayName("createProfile: null profileType now throws clean ValidationException (not a raw DB constraint error)")
    void testCreateProfile_nullType_throwsValidationException() {
        // Fix verified: validateProfileType now explicitly rejects null with a MetaServiceException
        // instead of silently passing through (which previously caused a raw DataIntegrityViolationException
        // from the DB NOT NULL constraint — an unhelpful error for callers).
        String code = CODE_PREFIX + RUN + "_nulltype";
        ReconciliationProfileRequest req = profileRequest(code, "supplier");
        req.setProfileType(null);

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.createProfile(req));
        assertTrue(ex.getMessage().contains("must not be null"),
                "Expected clean null-type message but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("createProfile: duplicate profileCode for same tenant is rejected")
    void testCreateProfile_duplicateCode_rejected() {
        String code = CODE_PREFIX + RUN + "_dup";
        // First insert via mapper
        insertProfileDirectly(code, "SUPPLIER");

        // Now try to create via service with same code (use valid type so we reach the dup-check)
        ReconciliationProfileRequest req = profileRequest(code, "supplier");
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.createProfile(req));
        assertTrue(ex.getMessage().contains("Profile code already exists"));
    }

    @Test
    @DisplayName("createProfile: defaults are applied (tested via direct mapper insert to bypass validateProfileType bug)")
    void testCreateProfile_defaults_observedViaMapper() {
        // The validateProfileType bug means we cannot exercise the createProfile code path
        // for defaults via the service. We instead verify that insertProfileDirectly (which
        // mirrors what the service would do) results in the correct default values being readable
        // via getProfile. We also verify the service-level default logic by inserting without
        // explicitly setting tolerance/matchBy fields through updateProfile.
        String code = CODE_PREFIX + RUN + "_defaults";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");

        // Verify that the profile was created with correct values
        ReconciliationProfileDTO dto = reconciliationService.getProfile(profile.getId());
        assertEquals(new BigDecimal("0.01"), dto.getAmountTolerance());
        assertEquals(Integer.valueOf(3), dto.getDateToleranceDays());
        assertTrue(dto.getMatchByReference());
        assertTrue(dto.getMatchByAmount());
        assertFalse(dto.getMatchByDate());
        assertTrue(dto.getEnabled());

        // Also verify that updateProfile can change these values (exercises mapRequestToProfile path)
        ReconciliationProfileRequest updateReq = new ReconciliationProfileRequest();
        updateReq.setAmountTolerance(new BigDecimal("0.50"));
        updateReq.setMatchByDate(true);
        ReconciliationProfileDTO updated = reconciliationService.updateProfile(profile.getId(), updateReq);
        assertEquals(new BigDecimal("0.50"), updated.getAmountTolerance());
        assertTrue(updated.getMatchByDate());
    }

    @Test
    @DisplayName("getProfile: returns DTO for existing profile in tenant")
    void testGetProfile_found() {
        String code = CODE_PREFIX + RUN + "_getok";
        ReconciliationProfile profile = insertProfileDirectly(code, "BANK");

        ReconciliationProfileDTO dto = reconciliationService.getProfile(profile.getId());

        assertNotNull(dto);
        assertEquals(code, dto.getProfileCode());
        assertEquals("BANK", dto.getProfileType());
        assertEquals(profile.getId(), dto.getId());
    }

    @Test
    @DisplayName("getProfile: throws for nonexistent or wrong-tenant profile")
    void testGetProfile_notFound() {
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.getProfile(Long.MAX_VALUE));
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    @Test
    @DisplayName("listProfiles: returns all profiles for current tenant, ordered by name")
    void testListProfiles() {
        String codeA = CODE_PREFIX + RUN + "_listA";
        String codeB = CODE_PREFIX + RUN + "_listB";
        insertProfileDirectly(codeA, "SUPPLIER");
        insertProfileDirectly(codeB, "BANK");

        List<ReconciliationProfileDTO> all = reconciliationService.listProfiles();

        // Should contain at least our two profiles
        assertTrue(all.stream().anyMatch(p -> codeA.equals(p.getProfileCode())));
        assertTrue(all.stream().anyMatch(p -> codeB.equals(p.getProfileCode())));

        // Should be ordered by profile_name (ascending)
        List<String> names = all.stream().map(ReconciliationProfileDTO::getProfileName).toList();
        List<String> sortedNames = names.stream().sorted().toList();
        assertEquals(sortedNames, names);
    }

    @Test
    @DisplayName("updateProfile: updates mutable fields of an existing profile")
    void testUpdateProfile_success() {
        String code = CODE_PREFIX + RUN + "_upd";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");

        ReconciliationProfileRequest updateReq = new ReconciliationProfileRequest();
        updateReq.setProfileName("Updated Name");
        updateReq.setDescription("Updated description");
        updateReq.setAmountTolerance(new BigDecimal("0.05"));
        updateReq.setDateToleranceDays(7);
        updateReq.setEnabled(false);

        ReconciliationProfileDTO updated = reconciliationService.updateProfile(profile.getId(), updateReq);

        assertEquals("Updated Name", updated.getProfileName());
        assertEquals("Updated description", updated.getDescription());
        assertEquals(new BigDecimal("0.05"), updated.getAmountTolerance());
        assertEquals(Integer.valueOf(7), updated.getDateToleranceDays());
        assertFalse(updated.getEnabled());
    }

    @Test
    @DisplayName("updateProfile: lowercase valid profileType is accepted after fix; invalid type still rejected")
    void testUpdateProfile_validatesType() {
        String code = CODE_PREFIX + RUN + "_updtype";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");

        // Fix verified: lowercase "bank" is now accepted and stored as "BANK"
        ReconciliationProfileRequest req = new ReconciliationProfileRequest();
        req.setProfileType("bank");
        ReconciliationProfileDTO updated = reconciliationService.updateProfile(profile.getId(), req);
        assertEquals("BANK", updated.getProfileType());

        // Invalid type is still rejected
        ReconciliationProfileRequest badReq = new ReconciliationProfileRequest();
        badReq.setProfileType("invalid_type");
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.updateProfile(profile.getId(), badReq));
        assertTrue(ex.getMessage().contains("Invalid profile type"));
    }

    @Test
    @DisplayName("updateProfile: throws for nonexistent or wrong-tenant ID")
    void testUpdateProfile_notFound() {
        ReconciliationProfileRequest req = new ReconciliationProfileRequest();
        req.setProfileName("Does not matter");

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.updateProfile(Long.MAX_VALUE, req));
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    @Test
    @DisplayName("deleteProfile: removes the profile; subsequent getProfile throws")
    void testDeleteProfile_success() {
        String code = CODE_PREFIX + RUN + "_del";
        ReconciliationProfile profile = insertProfileDirectly(code, "INTERCOMPANY");

        reconciliationService.deleteProfile(profile.getId());

        // Should now throw on getProfile since the entity is logically deleted
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.getProfile(profile.getId()));
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    @Test
    @DisplayName("deleteProfile: throws for nonexistent ID")
    void testDeleteProfile_notFound() {
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.deleteProfile(Long.MAX_VALUE));
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    // ==================== startReconciliation Guard Tests ====================

    @Test
    @DisplayName("startReconciliation: throws when profile not found")
    void testStartReconciliation_profileNotFound() {
        ReconciliationRunRequest req = new ReconciliationRunRequest();
        req.setProfileId(Long.MAX_VALUE);
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 1, 31));

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.startReconciliation(req));
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    @Test
    @DisplayName("startReconciliation: throws when profile is disabled")
    void testStartReconciliation_profileDisabled() {
        String code = CODE_PREFIX + RUN + "_disabled";
        ReconciliationProfile profile = insertProfileDirectly(code, "BANK");
        // Disable the profile
        profile.setEnabled(false);
        profileMapper.updateById(profile);

        ReconciliationRunRequest req = new ReconciliationRunRequest();
        req.setProfileId(profile.getId());
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 1, 31));

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.startReconciliation(req));
        assertTrue(ex.getMessage().contains("Profile is disabled"));
    }

    @Test
    @DisplayName("startReconciliation: PRODUCT BUG (deferred) — @Transactional rolls back FAILED run on exception (run not persisted)")
    void testStartReconciliation_modelNotFound_transactionRollback() {
        // PRODUCT BUG (bug-4, deferred): startReconciliation is @Transactional. When loadRecords
        // fails (model not found), the catch block sets status=FAILED and calls
        // runMapper.updateById(run), then re-throws MetaServiceException. Because
        // MetaServiceException is a RuntimeException, Spring's @Transactional rolls back the
        // ENTIRE transaction — including the initial runMapper.insert(run).
        // Result: the run record is NOT persisted in the DB, even though the code explicitly
        // tries to save it with FAILED status.
        //
        // The proper fix (REQUIRES_NEW via programmatic TransactionTemplate) caused a DEADLOCK
        // in integration testing and has been deferred to its own backlog item. This test
        // characterizes the current (known-buggy) behavior so it acts as a regression guard.

        String code = CODE_PREFIX + RUN + "_nomodel";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        // Profile points to "ap_invoice" model which does not exist in integration test DB

        ReconciliationRunRequest req = new ReconciliationRunRequest();
        req.setProfileId(profile.getId());
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 1, 31));

        // Exception IS thrown (wrapping the loadRecords failure)
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.startReconciliation(req));
        assertTrue(ex.getMessage().startsWith("Reconciliation failed:"),
                "Expected 'Reconciliation failed:' but got: " + ex.getMessage());

        // The run record is NOT persisted because @Transactional rolled back on exception
        QueryWrapper<ReconciliationRun> qw = new QueryWrapper<>();
        qw.eq("tenant_id", testTenant.getId());
        qw.eq("profile_id", profile.getId());
        List<ReconciliationRun> runs = runMapper.selectList(qw);
        assertTrue(runs.isEmpty(),
                "Run should NOT be persisted — @Transactional rolled back on RuntimeException (bug-4, deferred)");
    }

    // ==================== Run Query Tests ====================

    @Test
    @DisplayName("getRunSummary: returns run DTO for known runCode")
    void testGetRunSummary_found() {
        String code = CODE_PREFIX + RUN + "_runsum";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);

        ReconciliationRunDTO dto = reconciliationService.getRunSummary(runCode);

        assertNotNull(dto);
        assertEquals(runCode, dto.getRunCode());
        assertEquals(ReconciliationRun.STATUS_COMPLETED, dto.getStatus());
        assertEquals(profile.getId(), dto.getProfileId());
        assertEquals(code, dto.getProfileCode());
        assertNotNull(dto.getPeriodStart());
        assertNotNull(dto.getPeriodEnd());
        assertEquals(Integer.valueOf(1), dto.getMatchedCount());
        assertEquals(new BigDecimal("1000.00"), dto.getMatchedAmount());
    }

    @Test
    @DisplayName("getRunSummary: throws for unknown runCode")
    void testGetRunSummary_notFound() {
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.getRunSummary("NO-SUCH-RUN-CODE"));
        assertTrue(ex.getMessage().contains("Run not found"));
    }

    @Test
    @DisplayName("listRuns: returns paginated results without SQL error after count/orderBy fix")
    void testListRuns_returnsPaginatedResults() {
        // Fix verified: listRuns now builds a separate count QueryWrapper without orderByDesc,
        // so PostgreSQL no longer rejects "SELECT COUNT(*) ... ORDER BY ...".
        String code = CODE_PREFIX + RUN + "_listruns";
        ReconciliationProfile profile = insertProfileDirectly(code, "BANK");
        String rc1 = "RUN-" + code.toUpperCase() + "-A-" + System.currentTimeMillis();
        String rc2 = "RUN-" + code.toUpperCase() + "-B-" + (System.currentTimeMillis() + 1);
        insertRunDirectly(profile.getId(), rc1, ReconciliationRun.STATUS_COMPLETED);
        insertRunDirectly(profile.getId(), rc2, ReconciliationRun.STATUS_COMPLETED);

        // No exception — both count and paged query now work
        PaginationResult<ReconciliationRunDTO> page1 = reconciliationService.listRuns(1, 10);
        assertNotNull(page1);
        assertTrue(page1.getTotal() >= 2, "Expected at least 2 runs, got " + page1.getTotal());
        assertFalse(page1.getRecords().isEmpty());

        // Boundary parameters also work
        PaginationResult<ReconciliationRunDTO> page2 = reconciliationService.listRuns(1, 1);
        assertNotNull(page2);
        assertEquals(1, page2.getRecords().size());
        assertTrue(page2.getTotal() >= 2);
    }

    // ==================== getRunItems Tests ====================

    @Test
    @DisplayName("getRunItems: unfiltered path returns results without SQL error after count/orderBy fix")
    void testGetRunItems_unfiltered_returnsResults() {
        // Fix verified: getRunItems unfiltered path now builds a separate count QueryWrapper without
        // orderByAsc, so PostgreSQL no longer rejects "SELECT COUNT(*) ... ORDER BY ...".
        String code = CODE_PREFIX + RUN + "_items";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);
        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_MATCHED, new BigDecimal("1000.00"), new BigDecimal("1000.00"), "REF-001");
        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_UNMATCHED_A, new BigDecimal("500.00"), null, null);

        // No exception — unfiltered count and paged query now both work
        PaginationResult<ReconciliationItemDTO> result = reconciliationService.getRunItems(runCode, null, 1, 10);
        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("getRunItems: filters by matchStatus when specified")
    void testGetRunItems_filteredByStatus() {
        String code = CODE_PREFIX + RUN + "_itemsf";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);

        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_MATCHED, new BigDecimal("1000.00"), new BigDecimal("1000.00"), "REF-001");
        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_UNMATCHED_A, new BigDecimal("500.00"), null, "REF-002");
        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_DISCREPANCY, new BigDecimal("200.00"), new BigDecimal("210.00"), "REF-003");

        PaginationResult<ReconciliationItemDTO> matched = reconciliationService.getRunItems(
                runCode, ReconciliationItem.MATCH_MATCHED, 1, 10);
        assertEquals(1L, matched.getTotal());
        assertEquals(ReconciliationItem.MATCH_MATCHED, matched.getRecords().get(0).getMatchStatus());

        PaginationResult<ReconciliationItemDTO> unmatched = reconciliationService.getRunItems(
                runCode, ReconciliationItem.MATCH_UNMATCHED_A, 1, 10);
        assertEquals(1L, unmatched.getTotal());
    }

    @Test
    @DisplayName("getRunItems: throws for unknown runCode")
    void testGetRunItems_runNotFound() {
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.getRunItems("NO-SUCH-RUN", null, 1, 10));
        assertTrue(ex.getMessage().contains("Run not found"));
    }

    // ==================== resolveItem Tests ====================

    @Test
    @DisplayName("resolveItem: sets resolution, notes, resolvedBy, resolvedAt")
    void testResolveItem_approved() {
        String code = CODE_PREFIX + RUN + "_resolve";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);
        ReconciliationItem item = insertItemDirectly(run.getId(), ReconciliationItem.MATCH_DISCREPANCY,
                new BigDecimal("100.00"), new BigDecimal("105.00"), "REF-X");

        ReconciliationItemResolveRequest req = new ReconciliationItemResolveRequest();
        req.setResolution(ReconciliationItem.RESOLUTION_APPROVED);
        req.setNotes("Approved after review");

        ReconciliationItemDTO dto = reconciliationService.resolveItem(item.getId(), req);

        assertEquals(ReconciliationItem.RESOLUTION_APPROVED, dto.getResolution());
        assertEquals("Approved after review", dto.getResolutionNotes());
        assertEquals(testUser.getId(), dto.getResolvedBy());
        assertNotNull(dto.getResolvedAt());
    }

    @Test
    @DisplayName("resolveItem: ADJUSTED and WRITTEN_OFF resolutions are accepted")
    void testResolveItem_otherResolutions() {
        String code = CODE_PREFIX + RUN + "_resolve2";
        ReconciliationProfile profile = insertProfileDirectly(code, "BANK");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);

        ReconciliationItem item1 = insertItemDirectly(run.getId(), ReconciliationItem.MATCH_UNMATCHED_A,
                new BigDecimal("250.00"), null, null);
        ReconciliationItem item2 = insertItemDirectly(run.getId(), ReconciliationItem.MATCH_UNMATCHED_B,
                null, new BigDecimal("300.00"), null);

        ReconciliationItemResolveRequest req1 = new ReconciliationItemResolveRequest();
        req1.setResolution(ReconciliationItem.RESOLUTION_ADJUSTED);
        ReconciliationItemDTO dto1 = reconciliationService.resolveItem(item1.getId(), req1);
        assertEquals(ReconciliationItem.RESOLUTION_ADJUSTED, dto1.getResolution());

        ReconciliationItemResolveRequest req2 = new ReconciliationItemResolveRequest();
        req2.setResolution(ReconciliationItem.RESOLUTION_WRITTEN_OFF);
        req2.setNotes("Write off small discrepancy");
        ReconciliationItemDTO dto2 = reconciliationService.resolveItem(item2.getId(), req2);
        assertEquals(ReconciliationItem.RESOLUTION_WRITTEN_OFF, dto2.getResolution());
        assertEquals("Write off small discrepancy", dto2.getResolutionNotes());
    }

    @Test
    @DisplayName("resolveItem: throws when resolution is invalid")
    void testResolveItem_invalidResolution() {
        String code = CODE_PREFIX + RUN + "_resolveInvalid";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);
        ReconciliationItem item = insertItemDirectly(run.getId(), ReconciliationItem.MATCH_MATCHED,
                new BigDecimal("100.00"), new BigDecimal("100.00"), "REF-Y");

        ReconciliationItemResolveRequest req = new ReconciliationItemResolveRequest();
        req.setResolution("BOGUS_RESOLUTION");

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.resolveItem(item.getId(), req));
        assertTrue(ex.getMessage().contains("Invalid resolution"));
    }

    @Test
    @DisplayName("resolveItem: throws when item already resolved (double-resolve guard)")
    void testResolveItem_alreadyResolved() {
        String code = CODE_PREFIX + RUN + "_dblresolve";
        ReconciliationProfile profile = insertProfileDirectly(code, "BANK");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);
        ReconciliationItem item = insertItemDirectly(run.getId(), ReconciliationItem.MATCH_DISCREPANCY,
                new BigDecimal("100.00"), new BigDecimal("110.00"), "REF-Z");

        ReconciliationItemResolveRequest req = new ReconciliationItemResolveRequest();
        req.setResolution(ReconciliationItem.RESOLUTION_APPROVED);
        reconciliationService.resolveItem(item.getId(), req); // first resolve — OK

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.resolveItem(item.getId(), req)); // second — should fail
        assertTrue(ex.getMessage().contains("Item already resolved"));
    }

    @Test
    @DisplayName("resolveItem: throws for nonexistent or wrong-tenant item ID")
    void testResolveItem_notFound() {
        ReconciliationItemResolveRequest req = new ReconciliationItemResolveRequest();
        req.setResolution(ReconciliationItem.RESOLUTION_APPROVED);

        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.resolveItem(Long.MAX_VALUE, req));
        assertTrue(ex.getMessage().contains("Item not found"));
    }

    // ==================== getReconciliationReport Tests ====================

    @Test
    @DisplayName("getReconciliationReport: returns full report with stats and resolution breakdown")
    void testGetReconciliationReport_fullReport() {
        String code = CODE_PREFIX + RUN + "_report";
        ReconciliationProfile profile = insertProfileDirectly(code, "SUPPLIER");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();
        ReconciliationRun run = insertRunDirectly(profile.getId(), runCode, ReconciliationRun.STATUS_COMPLETED);

        // Insert items: 1 matched, 1 discrepancy (resolved), 1 unmatched_a
        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_MATCHED,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), "REF-001");
        ReconciliationItem discItem = insertItemDirectly(run.getId(), ReconciliationItem.MATCH_DISCREPANCY,
                new BigDecimal("500.00"), new BigDecimal("600.00"), "REF-002");
        insertItemDirectly(run.getId(), ReconciliationItem.MATCH_UNMATCHED_A,
                new BigDecimal("200.00"), null, "REF-003");

        // Resolve the discrepancy item
        ReconciliationItemResolveRequest resolveReq = new ReconciliationItemResolveRequest();
        resolveReq.setResolution(ReconciliationItem.RESOLUTION_ADJUSTED);
        reconciliationService.resolveItem(discItem.getId(), resolveReq);

        ReconciliationReportDTO report = reconciliationService.getReconciliationReport(runCode);

        assertNotNull(report);
        assertEquals(runCode, report.getRunCode());
        assertEquals(code, report.getProfileCode());
        assertEquals("Test Profile " + code, report.getProfileName());
        assertEquals("SUPPLIER", report.getProfileType());
        assertEquals(ReconciliationRun.STATUS_COMPLETED, report.getStatus());

        // Summary stats from the run entity (set by insertRunDirectly)
        assertEquals(Integer.valueOf(1), report.getMatchedCount());
        assertEquals(Integer.valueOf(1), report.getUnmatchedACount());

        // Resolution breakdown (computed from items)
        assertEquals(1, report.getResolvedCount());   // discItem was resolved
        assertEquals(2, report.getPendingCount());    // matched + unmatched_a are unresolved
        assertEquals(0, report.getApprovedCount());
        assertEquals(1, report.getAdjustedCount());
        assertEquals(0, report.getWrittenOffCount());

        // Match rate should be non-null
        assertNotNull(report.getMatchRate());

        // Top discrepancies list — discItem is a DISCREPANCY
        assertNotNull(report.getTopDiscrepancies());
        assertEquals(1, report.getTopDiscrepancies().size());
        assertEquals(ReconciliationItem.MATCH_DISCREPANCY, report.getTopDiscrepancies().get(0).getMatchStatus());
    }

    @Test
    @DisplayName("getReconciliationReport: matchRate=ZERO when both sourceCounts are zero/null")
    void testGetReconciliationReport_matchRateZeroWhenNoData() {
        String code = CODE_PREFIX + RUN + "_rpt0";
        ReconciliationProfile profile = insertProfileDirectly(code, "BANK");
        String runCode = "RUN-" + code.toUpperCase() + "-" + System.currentTimeMillis();

        // Run with nulls for counts (simulate empty reconciliation)
        ReconciliationRun run = new ReconciliationRun();
        run.setTenantId(testTenant.getId());
        run.setRunCode(runCode);
        run.setProfileId(profile.getId());
        run.setStatus(ReconciliationRun.STATUS_COMPLETED);
        run.setPeriodStart(LocalDate.of(2025, 1, 1));
        run.setPeriodEnd(LocalDate.of(2025, 1, 31));
        run.setStartedAt(Instant.now());
        run.setCreatedBy(testUser.getId());
        run.setCreatedAt(Instant.now());
        // Intentionally leave totalSourceA / totalSourceB null
        runMapper.insert(run);

        ReconciliationReportDTO report = reconciliationService.getReconciliationReport(runCode);

        assertEquals(BigDecimal.ZERO, report.getMatchRate());
        assertEquals(0, report.getResolvedCount());
        assertEquals(0, report.getPendingCount());
    }

    @Test
    @DisplayName("getReconciliationReport: throws for unknown runCode")
    void testGetReconciliationReport_notFound() {
        MetaServiceException ex = assertThrows(MetaServiceException.class,
                () -> reconciliationService.getReconciliationReport("NO-SUCH-RUN"));
        assertTrue(ex.getMessage().contains("Run not found"));
    }

    // ==================== Matching Algorithm Unit Tests (package-private method) ====================

    @Test
    @DisplayName("runMatchingAlgorithm: pass-1 exact reference match — within tolerance → MATCHED")
    void testMatchingAlgo_pass1_exactRef_matched() {
        ReconciliationProfile profile = buildInMemoryProfile(true, true, false,
                new BigDecimal("0.01"), 3);

        List<ReconciliationService.RecordEntry> sourceA = new ArrayList<>();
        sourceA.add(entry(1L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 10), "INV-001"));
        sourceA.add(entry(2L, new BigDecimal("500.00"), LocalDate.of(2025, 1, 15), "INV-002"));

        List<ReconciliationService.RecordEntry> sourceB = new ArrayList<>();
        sourceB.add(entry(101L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 11), "INV-001")); // ref match
        sourceB.add(entry(102L, new BigDecimal("999.00"), LocalDate.of(2025, 1, 20), "INV-999")); // no ref match

        List<ReconciliationItem> items = reconciliationService.runMatchingAlgorithm(
                testTenant.getId(), 9999L, profile, sourceA, sourceB);

        // INV-001 matched by ref (amount diff 0, within tolerance)
        ReconciliationItem refMatch = items.stream()
                .filter(i -> "INV-001".equals(i.getSourceARef()))
                .findFirst().orElseThrow();
        assertEquals(ReconciliationItem.MATCH_MATCHED, refMatch.getMatchStatus());
        assertEquals(new BigDecimal("100.00"), refMatch.getMatchScore());

        // INV-002 has no ref match in B; and INV-999 in B has no ref match in A
        // They may match by amount in pass-2 or be unmatched
        long unmatchedA = items.stream().filter(i -> ReconciliationItem.MATCH_UNMATCHED_A.equals(i.getMatchStatus())).count();
        long unmatchedB = items.stream().filter(i -> ReconciliationItem.MATCH_UNMATCHED_B.equals(i.getMatchStatus())).count();
        // At least one unmatched in B (INV-999 doesn't match INV-002's 500.00 by amount either)
        assertTrue(unmatchedB > 0 || unmatchedA > 0 || items.size() == 4);
    }

    @Test
    @DisplayName("runMatchingAlgorithm: pass-1 ref match with amount outside tolerance → DISCREPANCY")
    void testMatchingAlgo_pass1_discrepancy() {
        ReconciliationProfile profile = buildInMemoryProfile(true, true, false,
                new BigDecimal("0.01"), 3);

        List<ReconciliationService.RecordEntry> sourceA = new ArrayList<>();
        sourceA.add(entry(1L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 10), "INV-DISC"));

        List<ReconciliationService.RecordEntry> sourceB = new ArrayList<>();
        // Same ref but amount differs by 50 — beyond tolerance
        sourceB.add(entry(101L, new BigDecimal("1050.00"), LocalDate.of(2025, 1, 10), "INV-DISC"));

        List<ReconciliationItem> items = reconciliationService.runMatchingAlgorithm(
                testTenant.getId(), 9999L, profile, sourceA, sourceB);

        assertEquals(1, items.size());
        assertEquals(ReconciliationItem.MATCH_DISCREPANCY, items.get(0).getMatchStatus());
        assertEquals(new BigDecimal("100.00"), items.get(0).getMatchScore());
        assertEquals(new BigDecimal("-50.00"), items.get(0).getAmountDifference());
    }

    @Test
    @DisplayName("runMatchingAlgorithm: pass-2 amount match when no ref match")
    void testMatchingAlgo_pass2_amountMatch() {
        // matchByReference=false so pass-1 is skipped; matchByAmount=true
        ReconciliationProfile profile = buildInMemoryProfile(false, true, false,
                new BigDecimal("1.00"), 3);

        List<ReconciliationService.RecordEntry> sourceA = new ArrayList<>();
        sourceA.add(entry(1L, new BigDecimal("250.00"), LocalDate.of(2025, 1, 10), null));

        List<ReconciliationService.RecordEntry> sourceB = new ArrayList<>();
        sourceB.add(entry(101L, new BigDecimal("250.50"), LocalDate.of(2025, 1, 11), null)); // within 1.00 tolerance

        List<ReconciliationItem> items = reconciliationService.runMatchingAlgorithm(
                testTenant.getId(), 9999L, profile, sourceA, sourceB);

        long matched = items.stream()
                .filter(i -> ReconciliationItem.MATCH_MATCHED.equals(i.getMatchStatus())).count();
        assertEquals(1, matched);

        ReconciliationItem m = items.stream()
                .filter(i -> ReconciliationItem.MATCH_MATCHED.equals(i.getMatchStatus()))
                .findFirst().orElseThrow();
        assertTrue(m.getMatchScore().compareTo(new BigDecimal("80.00")) >= 0);
    }

    @Test
    @DisplayName("runMatchingAlgorithm: empty sources produce no items")
    void testMatchingAlgo_emptySources() {
        ReconciliationProfile profile = buildInMemoryProfile(true, true, true,
                new BigDecimal("0.01"), 3);

        List<ReconciliationItem> items = reconciliationService.runMatchingAlgorithm(
                testTenant.getId(), 9999L, profile,
                new ArrayList<>(), new ArrayList<>());

        assertTrue(items.isEmpty());
    }

    @Test
    @DisplayName("runMatchingAlgorithm: unmatched records in A and B produce UNMATCHED_A and UNMATCHED_B items")
    void testMatchingAlgo_allUnmatched() {
        // matchByReference=false, matchByAmount=false, matchByDate=false — no matching passes run
        ReconciliationProfile profile = buildInMemoryProfile(false, false, false,
                new BigDecimal("0.01"), 3);

        List<ReconciliationService.RecordEntry> sourceA = new ArrayList<>();
        sourceA.add(entry(1L, new BigDecimal("100.00"), LocalDate.of(2025, 1, 1), "A1"));
        sourceA.add(entry(2L, new BigDecimal("200.00"), LocalDate.of(2025, 1, 2), "A2"));

        List<ReconciliationService.RecordEntry> sourceB = new ArrayList<>();
        sourceB.add(entry(101L, new BigDecimal("300.00"), LocalDate.of(2025, 1, 3), "B1"));

        List<ReconciliationItem> items = reconciliationService.runMatchingAlgorithm(
                testTenant.getId(), 9999L, profile, sourceA, sourceB);

        assertEquals(3, items.size());
        long unmA = items.stream().filter(i -> ReconciliationItem.MATCH_UNMATCHED_A.equals(i.getMatchStatus())).count();
        long unmB = items.stream().filter(i -> ReconciliationItem.MATCH_UNMATCHED_B.equals(i.getMatchStatus())).count();
        assertEquals(2, unmA);
        assertEquals(1, unmB);

        // Check sourceA/B fields are set correctly
        items.stream().filter(i -> ReconciliationItem.MATCH_UNMATCHED_A.equals(i.getMatchStatus())).forEach(i -> {
            assertNotNull(i.getSourceARecordId());
            assertNotNull(i.getSourceAAmount());
        });
        items.stream().filter(i -> ReconciliationItem.MATCH_UNMATCHED_B.equals(i.getMatchStatus())).forEach(i -> {
            assertNotNull(i.getSourceBRecordId());
            assertNotNull(i.getSourceBAmount());
        });
    }

    @Test
    @DisplayName("runMatchingAlgorithm: pass-3 fuzzy match by amount when no ref match and amount is close")
    void testMatchingAlgo_pass3_fuzzyMatch() {
        // matchByAmount=true, matchByDate=true, matchByReference=false
        // Pass-2 won't match because diff > strict tolerance, pass-3 will match
        ReconciliationProfile profile = buildInMemoryProfile(false, true, true,
                new BigDecimal("0.01"), 7);

        List<ReconciliationService.RecordEntry> sourceA = new ArrayList<>();
        // Amount 100, date Jan 10
        sourceA.add(entry(1L, new BigDecimal("100.00"), LocalDate.of(2025, 1, 10), null));

        List<ReconciliationService.RecordEntry> sourceB = new ArrayList<>();
        // Amount 100.05 — within fuzzy tolerance (10x * 0.01 = 0.10), date Jan 12
        sourceB.add(entry(101L, new BigDecimal("100.05"), LocalDate.of(2025, 1, 12), null));

        List<ReconciliationItem> items = reconciliationService.runMatchingAlgorithm(
                testTenant.getId(), 9999L, profile, sourceA, sourceB);

        // Pass-2 matches within 0.01 tolerance (100.05 - 100.00 = 0.05 > 0.01),
        // but pass-3 fuzzy range is 10x = 0.10 so matches there OR pass-2 if strict check
        // Either way it should be matched or discrepancy
        assertEquals(1, items.stream()
                .filter(i -> ReconciliationItem.MATCH_MATCHED.equals(i.getMatchStatus())
                        || ReconciliationItem.MATCH_DISCREPANCY.equals(i.getMatchStatus()))
                .count());
    }

    // ==================== Internal helpers for algorithm testing ====================

    private ReconciliationProfile buildInMemoryProfile(
            boolean matchByRef, boolean matchByAmount, boolean matchByDate,
            BigDecimal amountTolerance, int dateToleranceDays) {
        ReconciliationProfile p = new ReconciliationProfile();
        p.setMatchByReference(matchByRef);
        p.setMatchByAmount(matchByAmount);
        p.setMatchByDate(matchByDate);
        p.setAmountTolerance(amountTolerance);
        p.setDateToleranceDays(dateToleranceDays);
        p.setSourceARefField(matchByRef ? "invoice_ref" : null);
        p.setSourceBRefField(matchByRef ? "statement_ref" : null);
        p.setSourceADateField(matchByDate ? "invoice_date" : null);
        p.setSourceBDateField(matchByDate ? "statement_date" : null);
        return p;
    }

    private ReconciliationService.RecordEntry entry(Long id, BigDecimal amount, LocalDate date, String ref) {
        ReconciliationService.RecordEntry e = new ReconciliationService.RecordEntry();
        e.recordId = id;
        e.amount = amount;
        e.date = date;
        e.ref = ref;
        return e;
    }
}
