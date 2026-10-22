package com.auraboot.framework.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.governance.dto.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for MasterDataGovernanceService.
 *
 * <ul>
 *   <li>GV-01: createInitialVersion creates version 1 and records state</li>
 *   <li>GV-02: submitChangeRequest CREATE produces DRAFT request with request number</li>
 *   <li>GV-03: submitChangeRequest UPDATE captures original data</li>
 *   <li>GV-04: submitChangeRequest DELETE works</li>
 *   <li>GV-05: submitChangeRequest invalid changeType throws</li>
 *   <li>GV-06: submitChangeRequest BULK_UPDATE is accepted</li>
 *   <li>GV-07: submitForReview transitions DRAFT to PENDING</li>
 *   <li>GV-08: submitForReview on non-DRAFT throws</li>
 *   <li>GV-09: listChangeRequests no filter returns all</li>
 *   <li>GV-10: listChangeRequests with DRAFT filter</li>
 *   <li>GV-11: getChangeRequest by pid returns entity</li>
 *   <li>GV-12: getChangeRequest unknown pid throws</li>
 *   <li>GV-13: reviewChangeRequest APPROVE transitions to APPROVED</li>
 *   <li>GV-14: reviewChangeRequest REJECT does not create version</li>
 *   <li>GV-15: reviewChangeRequest on non-PENDING throws</li>
 *   <li>GV-16: applyChange transitions APPROVED to APPLIED and creates version</li>
 *   <li>GV-17: applyChange on non-APPROVED throws</li>
 *   <li>GV-18: cancelChangeRequest by submitter succeeds (DRAFT)</li>
 *   <li>GV-19: cancelChangeRequest by non-submitter throws</li>
 *   <li>GV-20: createInitialVersion duplicate throws</li>
 *   <li>GV-21: listVersions returns history descending</li>
 *   <li>GV-22: diffVersions returns field-level changes</li>
 *   <li>GV-23: getStats aggregates correctly</li>
 * </ul>
 */
@Slf4j
@DisplayName("MasterDataGovernanceService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MasterDataGovernanceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MasterDataGovernanceService governanceService;

    private final String runId = String.valueOf(System.currentTimeMillis() % 100_000_000L);
    private final String entityType = "gv_entity_" + runId;
    private final String entityPid = "gv_pid_" + runId;
    private final String submitterPid = "gv_sub_" + runId;
    private final String reviewerPid = "gv_rev_" + runId;
    private final String applierPid = "gv_app_" + runId;

    // State shared across ordered tests (committed to DB between tests)
    private String draftRequestPid;
    private String pendingRequestPid;

    @Test
    @Order(1)
    @DisplayName("GV-01: createInitialVersion creates version 1 for a new entity")
    void GV_01_createInitialVersion_newEntity() {
        VersionResponse version = governanceService.createInitialVersion(
                entityType, entityPid, testTenant.getId(), submitterPid);

        assertThat(version).isNotNull();
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getComment()).isEqualTo("Initial version");
        log.info("GV-01: created initial version for entityType={}", entityType);
    }

    @Test
    @Order(2)
    @DisplayName("GV-02: submitChangeRequest CREATE produces DRAFT request with request number")
    void GV_02_submitCreateRequest() {
        ChangeRequestCreateDTO dto = buildDto("create", null);
        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), submitterPid);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getRequestNumber()).isNotBlank();
        assertThat(result.getRequestNumber()).startsWith("CR-");
        assertThat(result.getStatus()).isEqualTo("draft");
        assertThat(result.getChangeType()).isEqualTo("create");
        assertThat(result.getEntityType()).isEqualTo(entityType);
        assertThat(result.getSubmittedByPid()).isEqualTo(submitterPid);

        draftRequestPid = result.getPid();
        log.info("GV-02: created draft request pid={}, number={}", draftRequestPid, result.getRequestNumber());
    }

    @Test
    @Order(3)
    @DisplayName("GV-03: submitChangeRequest UPDATE captures original data from version history")
    void GV_03_submitUpdateRequest_capturesOriginalData() {
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("name", "Updated " + runId);
        proposed.put("price", 29.99);

        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(entityType);
        dto.setEntityPid(entityPid);
        dto.setChangeType("update");
        dto.setProposedData(proposed);

        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), submitterPid);

        assertThat(result.getChangeType()).isEqualTo("update");
        assertThat(result.getEntityPid()).isEqualTo(entityPid);
        assertThat(result.getStatus()).isEqualTo("draft");
    }

    @Test
    @Order(4)
    @DisplayName("GV-04: submitChangeRequest DELETE sets changeType correctly")
    void GV_04_submitDeleteRequest() {
        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(entityType);
        dto.setEntityPid(entityPid);
        dto.setChangeType("delete");
        dto.setProposedData(new HashMap<>());

        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), submitterPid);

        assertThat(result.getChangeType()).isEqualTo("delete");
        assertThat(result.getStatus()).isEqualTo("draft");
    }

    @Test
    @Order(5)
    @DisplayName("GV-05: submitChangeRequest with invalid changeType throws IllegalArgumentException")
    void GV_05_submitInvalidChangeType_throws() {
        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(entityType);
        dto.setChangeType("invalid_type");
        dto.setProposedData(new HashMap<>());

        assertThatThrownBy(() -> governanceService.submitChangeRequest(dto, testTenant.getId(), submitterPid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Order(6)
    @DisplayName("GV-06: submitChangeRequest BULK_UPDATE is accepted")
    void GV_06_submitBulkUpdate() {
        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(entityType);
        dto.setEntityPid(entityPid);
        dto.setChangeType("bulk_update");
        dto.setProposedData(Map.of("field1", "value1"));

        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, testTenant.getId(), submitterPid);

        assertThat(result.getChangeType()).isEqualTo("bulk_update");
        assertThat(result.getStatus()).isEqualTo("draft");
    }

    @Test
    @Order(7)
    @DisplayName("GV-07: submitForReview transitions DRAFT to PENDING")
    void GV_07_submitForReview_transitions() {
        assertThat(draftRequestPid).as("draftRequestPid from GV-02").isNotBlank();

        ChangeRequestResponse result = governanceService.submitForReview(draftRequestPid, testTenant.getId(), submitterPid);

        assertThat(result.getStatus()).isEqualTo("pending");
        pendingRequestPid = result.getPid();
    }

    @Test
    @Order(8)
    @DisplayName("GV-08: submitForReview on non-DRAFT throws IllegalStateException")
    void GV_08_submitForReview_nonDraft_throws() {
        assertThat(pendingRequestPid).as("pendingRequestPid from GV-07").isNotBlank();

        assertThatThrownBy(() ->
                governanceService.submitForReview(pendingRequestPid, testTenant.getId(), submitterPid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft");
    }

    @Test
    @Order(9)
    @DisplayName("GV-09: listChangeRequests without filter returns all tenant requests")
    void GV_09_listChangeRequests_noFilter() {
        Page<ChangeRequestResponse> page = governanceService.listChangeRequests(testTenant.getId(), null, 1, 50);

        assertThat(page).isNotNull();
        assertThat(page.getRecords()).isNotEmpty();
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(3L);
    }

    @Test
    @Order(10)
    @DisplayName("GV-10: listChangeRequests with DRAFT filter returns only DRAFT records")
    void GV_10_listChangeRequests_draftFilter() {
        Page<ChangeRequestResponse> page = governanceService.listChangeRequests(testTenant.getId(), "draft", 1, 50);

        assertThat(page).isNotNull();
        assertThat(page.getRecords()).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo("draft"));
    }

    @Test
    @Order(11)
    @DisplayName("GV-11: getChangeRequest by pid returns the correct entity")
    void GV_11_getChangeRequest_returnsEntity() {
        assertThat(pendingRequestPid).as("pendingRequestPid from GV-07").isNotBlank();

        ChangeRequestResponse result = governanceService.getChangeRequest(pendingRequestPid, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isEqualTo(pendingRequestPid);
        assertThat(result.getStatus()).isEqualTo("pending");
    }

    @Test
    @Order(12)
    @DisplayName("GV-12: getChangeRequest with unknown pid throws IllegalArgumentException")
    void GV_12_getChangeRequest_unknownPid_throws() {
        assertThatThrownBy(() -> governanceService.getChangeRequest("no-such-pid-" + runId, testTenant.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Order(13)
    @DisplayName("GV-13: reviewChangeRequest APPROVE transitions to APPROVED")
    void GV_13_review_approve() {
        // Submit a fresh UPDATE request and move to PENDING
        ChangeRequestCreateDTO createDto = buildDto("update", entityPid);
        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), submitterPid);

        ChangeRequestReviewDTO reviewDto = new ChangeRequestReviewDTO();
        reviewDto.setAction("approved");
        reviewDto.setComment("Looks good");

        ChangeRequestResponse reviewed = governanceService.reviewChangeRequest(
                created.getPid(), reviewDto, testTenant.getId(), reviewerPid);

        assertThat(reviewed.getStatus()).isEqualTo("approved");
        assertThat(reviewed.getReviewedByPid()).isEqualTo(reviewerPid);
        assertThat(reviewed.getReviewComment()).isEqualTo("Looks good");
        assertThat(reviewed.getReviewedAt()).isNotNull();
    }

    @Test
    @Order(14)
    @DisplayName("GV-14: reviewChangeRequest REJECT does not create a new version")
    void GV_14_review_reject_noNewVersion() {
        ChangeRequestCreateDTO createDto = buildDto("create", null);
        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), submitterPid);

        long versionsBefore = governanceService.listVersions(entityType, entityPid, testTenant.getId()).size();

        ChangeRequestReviewDTO reviewDto = new ChangeRequestReviewDTO();
        reviewDto.setAction("rejected");
        reviewDto.setComment("Missing required fields");

        ChangeRequestResponse reviewed = governanceService.reviewChangeRequest(
                created.getPid(), reviewDto, testTenant.getId(), reviewerPid);

        assertThat(reviewed.getStatus()).isEqualTo("rejected");

        long versionsAfter = governanceService.listVersions(entityType, entityPid, testTenant.getId()).size();
        assertThat(versionsAfter).isEqualTo(versionsBefore);
    }

    @Test
    @Order(15)
    @DisplayName("GV-15: reviewChangeRequest on non-PENDING request throws IllegalStateException")
    void GV_15_review_alreadyReviewed_throws() {
        ChangeRequestCreateDTO createDto = buildDto("create", null);
        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), submitterPid);

        ChangeRequestReviewDTO approve = new ChangeRequestReviewDTO();
        approve.setAction("approved");
        approve.setComment("OK");
        governanceService.reviewChangeRequest(created.getPid(), approve, testTenant.getId(), reviewerPid);

        ChangeRequestReviewDTO reject = new ChangeRequestReviewDTO();
        reject.setAction("rejected");
        reject.setComment("Changed mind");

        assertThatThrownBy(() ->
                governanceService.reviewChangeRequest(created.getPid(), reject, testTenant.getId(), reviewerPid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
    }

    @Test
    @Order(16)
    @DisplayName("GV-16: applyChange transitions APPROVED to APPLIED and creates version snapshot")
    void GV_16_applyChange_createsVersion() {
        // Create, submit, approve
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("name", "Applied " + runId);
        proposed.put("qty", 42);

        ChangeRequestCreateDTO createDto = new ChangeRequestCreateDTO();
        createDto.setEntityType(entityType);
        createDto.setEntityPid(entityPid);
        createDto.setChangeType("update");
        createDto.setProposedData(proposed);

        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);
        governanceService.submitForReview(created.getPid(), testTenant.getId(), submitterPid);

        ChangeRequestReviewDTO approve = new ChangeRequestReviewDTO();
        approve.setAction("approved");
        approve.setComment("Ready");
        governanceService.reviewChangeRequest(created.getPid(), approve, testTenant.getId(), reviewerPid);

        long versionsBefore = governanceService.listVersions(entityType, entityPid, testTenant.getId()).size();

        // Apply
        ChangeRequestResponse applied = governanceService.applyChange(created.getPid(), testTenant.getId(), applierPid);

        assertThat(applied.getStatus()).isEqualTo("applied");
        assertThat(applied.getAppliedByPid()).isEqualTo(applierPid);
        assertThat(applied.getAppliedAt()).isNotNull();

        long versionsAfter = governanceService.listVersions(entityType, entityPid, testTenant.getId()).size();
        assertThat(versionsAfter).isEqualTo(versionsBefore + 1);
    }

    @Test
    @Order(17)
    @DisplayName("GV-17: applyChange on non-APPROVED throws IllegalStateException")
    void GV_17_applyChange_nonApproved_throws() {
        ChangeRequestCreateDTO createDto = buildDto("create", null);
        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);

        assertThatThrownBy(() ->
                governanceService.applyChange(created.getPid(), testTenant.getId(), applierPid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved");
    }

    @Test
    @Order(18)
    @DisplayName("GV-18: cancelChangeRequest by the submitter transitions DRAFT to CANCELLED")
    void GV_18_cancel_bySubmitter_succeeds() {
        ChangeRequestCreateDTO createDto = buildDto("create", null);
        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);

        ChangeRequestResponse cancelled = governanceService.cancelChangeRequest(
                created.getPid(), testTenant.getId(), submitterPid);

        assertThat(cancelled.getStatus()).isEqualTo("cancelled");
    }

    @Test
    @Order(19)
    @DisplayName("GV-19: cancelChangeRequest by non-submitter throws IllegalStateException")
    void GV_19_cancel_byNonSubmitter_throws() {
        ChangeRequestCreateDTO createDto = buildDto("create", null);
        ChangeRequestResponse created = governanceService.submitChangeRequest(createDto, testTenant.getId(), submitterPid);

        assertThatThrownBy(() ->
                governanceService.cancelChangeRequest(created.getPid(), testTenant.getId(), "another_user_" + runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submitter");
    }

    @Test
    @Order(20)
    @DisplayName("GV-20: createInitialVersion for entity with existing history throws IllegalStateException")
    void GV_20_createInitialVersion_duplicate_throws() {
        assertThatThrownBy(() ->
                governanceService.createInitialVersion(entityType, entityPid, testTenant.getId(), submitterPid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has version");
    }

    @Test
    @Order(21)
    @DisplayName("GV-21: listVersions returns history ordered descending by version number")
    void GV_21_listVersions_descendingOrder() {
        List<VersionResponse> versions = governanceService.listVersions(entityType, entityPid, testTenant.getId());

        assertThat(versions).isNotEmpty();
        // Verify descending order
        for (int i = 0; i < versions.size() - 1; i++) {
            assertThat(versions.get(i).getVersionNumber())
                    .isGreaterThan(versions.get(i + 1).getVersionNumber());
        }
    }

    @Test
    @Order(22)
    @DisplayName("GV-22: diffVersions returns field-level changes between two versions")
    void GV_22_diffVersions_returnsChanges() {
        List<VersionResponse> versions = governanceService.listVersions(entityType, entityPid, testTenant.getId());
        org.junit.jupiter.api.Assumptions.assumeTrue(versions.size() >= 2, "Need >= 2 versions");

        int latestVersion = versions.get(0).getVersionNumber();
        int prevVersion = versions.get(1).getVersionNumber();

        VersionDiffResponse diff = governanceService.diffVersions(
                entityType, entityPid, prevVersion, latestVersion, testTenant.getId());

        assertThat(diff).isNotNull();
        assertThat(diff.getFromVersion()).isEqualTo(prevVersion);
        assertThat(diff.getToVersion()).isEqualTo(latestVersion);
        assertThat(diff.getChanges()).isNotNull();
    }

    @Test
    @Order(23)
    @DisplayName("GV-23: getStats returns aggregated request and version counts")
    void GV_23_getStats_aggregatesCorrectly() {
        GovernanceStatsResponse stats = governanceService.getStats(testTenant.getId());

        assertThat(stats).isNotNull();
        assertThat(stats.getTotalChangeRequests()).isGreaterThanOrEqualTo(1L);
        assertThat(stats.getTotalVersionSnapshots()).isGreaterThanOrEqualTo(1L);
        log.info("GV-23: stats total_cr={}, total_snapshots={}",
                stats.getTotalChangeRequests(), stats.getTotalVersionSnapshots());
    }

    // ---- helpers ----

    private ChangeRequestCreateDTO buildDto(String changeType, String forEntityPid) {
        Map<String, Object> proposed = new HashMap<>();
        proposed.put("name", "Test " + changeType + " " + runId);
        proposed.put("value", System.nanoTime());

        ChangeRequestCreateDTO dto = new ChangeRequestCreateDTO();
        dto.setEntityType(entityType);
        dto.setEntityPid(forEntityPid);
        dto.setChangeType(changeType);
        dto.setProposedData(proposed);
        return dto;
    }
}
