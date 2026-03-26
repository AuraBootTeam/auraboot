package com.auraboot.framework.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.governance.dto.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transactional (rollback) unit-style tests for MasterDataGovernanceService.
 * Each test runs in a fresh transaction that is rolled back after the test.
 * Complements MasterDataGovernanceServiceIntegrationTest (ordered, committed lifecycle tests).
 */
class MasterDataGovernanceServiceTest extends BaseIntegrationTest {

    @Autowired
    private MasterDataGovernanceService governanceService;

    private static final String SUBMITTER_PID = "submitter_" + System.currentTimeMillis();
    private static final String REVIEWER_PID  = "reviewer_"  + System.currentTimeMillis();
    private static final String APPLIER_PID   = "applier_"   + System.currentTimeMillis();
    private static final String ENTITY_TYPE   = "test_product_" + System.currentTimeMillis();
    private static final String ENTITY_PID    = "ent_" + System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        // testTenant and MetaContext are already set by BaseIntegrationTest.setupTenantContext()
        governanceService.createInitialVersion(ENTITY_TYPE, ENTITY_PID, testTenant.getId(), SUBMITTER_PID);
    }

    // ---- Submit Change Request ----

    @Test
    void testSubmitCreateRequest() {
        ChangeRequestCreateDTO dto = buildCreateDTO("create", null);
        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), SUBMITTER_PID);

        assertNotNull(result);
        assertNotNull(result.getPid());
        assertNotNull(result.getRequestNumber());
        assertTrue(result.getRequestNumber().startsWith("CR-"));
        assertEquals("draft", result.getStatus());
        assertEquals("create", result.getChangeType());
        assertEquals(ENTITY_TYPE, result.getEntityType());
        assertEquals(SUBMITTER_PID, result.getSubmittedByPid());
    }

    @Test
    void testSubmitUpdateRequest() {
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("name", "Updated Product");
        proposed.put("price", 29.99);

        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(ENTITY_TYPE);
        dto.setEntityPid(ENTITY_PID);
        dto.setChangeType("update");
        dto.setProposedData(proposed);

        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), SUBMITTER_PID);

        assertNotNull(result);
        assertEquals("update", result.getChangeType());
        assertEquals(ENTITY_PID, result.getEntityPid());
        assertEquals("draft", result.getStatus());
    }

    @Test
    void testSubmitDeleteRequest() {
        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(ENTITY_TYPE);
        dto.setEntityPid(ENTITY_PID);
        dto.setChangeType("delete");
        dto.setProposedData(new HashMap<>());

        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), SUBMITTER_PID);

        assertNotNull(result);
        assertEquals("delete", result.getChangeType());
        assertEquals("draft", result.getStatus());
    }

    @Test
    void testSubmitBulkUpdateRequest() {
        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(ENTITY_TYPE);
        dto.setEntityPid(ENTITY_PID);
        dto.setChangeType("bulk_update");
        dto.setProposedData(Map.of("status", "active"));

        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), SUBMITTER_PID);

        assertNotNull(result);
        assertEquals("bulk_update", result.getChangeType());
        assertEquals("draft", result.getStatus());
    }

    @Test
    void testSubmitInvalidChangeType() {
        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(ENTITY_TYPE);
        dto.setChangeType("invalid");
        dto.setProposedData(new HashMap<>());

        assertThrows(IllegalArgumentException.class, () ->
                governanceService.submitChangeRequest(dto, testTenant.getId(), SUBMITTER_PID));
    }

    // ---- Submit for Review ----

    @Test
    void testSubmitForReview() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        assertEquals("draft", created.getStatus());

        ChangeRequestResponse submitted = governanceService.submitForReview(
                created.getPid(), testTenant.getId(), SUBMITTER_PID);

        assertEquals("pending", submitted.getStatus());
    }

    @Test
    void testSubmitForReview_nonDraft_throws() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID);

        // Already PENDING, can't submit again
        assertThrows(IllegalStateException.class, () ->
                governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID));
    }

    // ---- List Change Requests ----

    @Test
    void testListChangeRequests() {
        governanceService.submitChangeRequest(buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitChangeRequest(buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        Page<ChangeRequestResponse> page = governanceService.listChangeRequests(testTenant.getId(), null, 1, 10);

        assertNotNull(page);
        assertTrue(page.getRecords().size() >= 2);
    }

    @Test
    void testListChangeRequestsWithStatusFilter() {
        governanceService.submitChangeRequest(buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        Page<ChangeRequestResponse> draftPage = governanceService.listChangeRequests(testTenant.getId(), "draft", 1, 10);
        assertTrue(draftPage.getRecords().size() >= 1);

        Page<ChangeRequestResponse> approvedPage = governanceService.listChangeRequests(testTenant.getId(), "approved", 1, 10);
        assertNotNull(approvedPage);
    }

    // ---- Get Single Change Request ----

    @Test
    void testGetChangeRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestResponse fetched = governanceService.getChangeRequest(created.getPid(), testTenant.getId());

        assertNotNull(fetched);
        assertEquals(created.getPid(), fetched.getPid());
        assertEquals("draft", fetched.getStatus());
    }

    @Test
    void testGetChangeRequestNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                governanceService.getChangeRequest("nonexistent_pid", testTenant.getId()));
    }

    // ---- Review Change Request ----

    @Test
    void testApproveChangeRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestReviewDTO reviewDTO = new ChangeRequestReviewDTO();
        reviewDTO.setAction("approved");
        reviewDTO.setComment("Looks good");

        ChangeRequestResponse reviewed = governanceService.reviewChangeRequest(
                created.getPid(), reviewDTO, testTenant.getId(), REVIEWER_PID);

        assertEquals("approved", reviewed.getStatus());
        assertEquals(REVIEWER_PID, reviewed.getReviewedByPid());
        assertEquals("Looks good", reviewed.getReviewComment());
        assertNotNull(reviewed.getReviewedAt());
    }

    @Test
    void testRejectChangeRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestReviewDTO reviewDTO = new ChangeRequestReviewDTO();
        reviewDTO.setAction("rejected");
        reviewDTO.setComment("Missing required fields");

        ChangeRequestResponse reviewed = governanceService.reviewChangeRequest(
                created.getPid(), reviewDTO, testTenant.getId(), REVIEWER_PID);

        assertEquals("rejected", reviewed.getStatus());
    }

    @Test
    void testReviewAlreadyReviewedRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestReviewDTO approveDTO = new ChangeRequestReviewDTO();
        approveDTO.setAction("approved");
        approveDTO.setComment("OK");
        governanceService.reviewChangeRequest(created.getPid(), approveDTO, testTenant.getId(), REVIEWER_PID);

        ChangeRequestReviewDTO rejectDTO = new ChangeRequestReviewDTO();
        rejectDTO.setAction("rejected");
        rejectDTO.setComment("Changed my mind");

        assertThrows(IllegalStateException.class, () ->
                governanceService.reviewChangeRequest(created.getPid(), rejectDTO, testTenant.getId(), REVIEWER_PID));
    }

    // ---- Apply Change Request ----

    @Test
    void testApplyChangeRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("update", ENTITY_PID), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestReviewDTO reviewDTO = new ChangeRequestReviewDTO();
        reviewDTO.setAction("approved");
        reviewDTO.setComment("Ready to apply");
        governanceService.reviewChangeRequest(created.getPid(), reviewDTO, testTenant.getId(), REVIEWER_PID);

        long versionsBefore = governanceService.listVersions(ENTITY_TYPE, ENTITY_PID, testTenant.getId()).size();

        ChangeRequestResponse applied = governanceService.applyChange(created.getPid(), testTenant.getId(), APPLIER_PID);

        assertEquals("applied", applied.getStatus());
        assertEquals(APPLIER_PID, applied.getAppliedByPid());
        assertNotNull(applied.getAppliedAt());

        long versionsAfter = governanceService.listVersions(ENTITY_TYPE, ENTITY_PID, testTenant.getId()).size();
        assertEquals(versionsBefore + 1, versionsAfter);
    }

    @Test
    void testApplyNonApprovedThrows() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        assertThrows(IllegalStateException.class, () ->
                governanceService.applyChange(created.getPid(), testTenant.getId(), APPLIER_PID));
    }

    // ---- Cancel Change Request ----

    @Test
    void testCancelDraftChangeRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestResponse cancelled = governanceService.cancelChangeRequest(
                created.getPid(), testTenant.getId(), SUBMITTER_PID);

        assertEquals("cancelled", cancelled.getStatus());
    }

    @Test
    void testCancelPendingChangeRequest() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestResponse cancelled = governanceService.cancelChangeRequest(
                created.getPid(), testTenant.getId(), SUBMITTER_PID);

        assertEquals("cancelled", cancelled.getStatus());
    }

    @Test
    void testCancelByNonSubmitterFails() {
        ChangeRequestResponse created = governanceService.submitChangeRequest(
                buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        assertThrows(IllegalStateException.class, () ->
                governanceService.cancelChangeRequest(created.getPid(), testTenant.getId(), "another_user"));
    }

    // ---- Version History ----

    @Test
    void testListVersions() {
        List<VersionResponse> versions = governanceService.listVersions(ENTITY_TYPE, ENTITY_PID, testTenant.getId());

        assertNotNull(versions);
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).getVersionNumber());
        assertEquals("Initial version", versions.get(0).getComment());
    }

    @Test
    void testCreateInitialVersionDuplicate() {
        assertThrows(IllegalStateException.class, () ->
                governanceService.createInitialVersion(ENTITY_TYPE, ENTITY_PID, testTenant.getId(), SUBMITTER_PID));
    }

    // ---- Version Diff ----

    @Test
    void testDiffVersions() {
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("name", "Updated Name");
        proposed.put("price", 42.0);

        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(ENTITY_TYPE);
        dto.setEntityPid(ENTITY_PID);
        dto.setChangeType("update");
        dto.setProposedData(proposed);

        ChangeRequestResponse cr = governanceService.submitChangeRequest(dto, testTenant.getId(), SUBMITTER_PID);
        governanceService.submitForReview(cr.getPid(), testTenant.getId(), SUBMITTER_PID);

        ChangeRequestReviewDTO reviewDTO = new ChangeRequestReviewDTO();
        reviewDTO.setAction("approved");
        reviewDTO.setComment("OK");
        governanceService.reviewChangeRequest(cr.getPid(), reviewDTO, testTenant.getId(), REVIEWER_PID);
        governanceService.applyChange(cr.getPid(), testTenant.getId(), APPLIER_PID);

        VersionDiffResponse diff = governanceService.diffVersions(ENTITY_TYPE, ENTITY_PID, 1, 2, testTenant.getId());

        assertNotNull(diff);
        assertEquals(1, diff.getFromVersion());
        assertEquals(2, diff.getToVersion());
        assertFalse(diff.getChanges().isEmpty());
    }

    @Test
    void testDiffVersionsNotFound() {
        assertThrows(IllegalArgumentException.class, () ->
                governanceService.diffVersions(ENTITY_TYPE, ENTITY_PID, 1, 99, testTenant.getId()));
    }

    // ---- Statistics ----

    @Test
    void testGetStats() {
        governanceService.submitChangeRequest(buildCreateDTO("create", null), testTenant.getId(), SUBMITTER_PID);

        GovernanceStatsResponse stats = governanceService.getStats(testTenant.getId());

        assertNotNull(stats);
        assertTrue(stats.getTotalChangeRequests() >= 1);
        assertTrue(stats.getPendingRequests() >= 1); // DRAFT counts as pending
        assertTrue(stats.getTotalVersionSnapshots() >= 1); // from setUp
    }

    // ---- Helpers ----

    private ChangeRequestCreateDTO buildCreateDTO(String changeType, String entityPid) {
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("name", "Test Product " + System.currentTimeMillis());
        proposed.put("sku", "SKU-" + System.currentTimeMillis());
        proposed.put("price", 19.99);

        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(ENTITY_TYPE);
        dto.setEntityPid(entityPid);
        dto.setChangeType(changeType);
        dto.setProposedData(proposed);
        return dto;
    }
}
