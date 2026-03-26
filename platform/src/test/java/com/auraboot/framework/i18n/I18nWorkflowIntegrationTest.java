package com.auraboot.framework.i18n;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the i18n translation workflow: DRAFT → REVIEW → APPROVED.
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>WF-01: submitReview transitions DRAFT → REVIEW</li>
 *   <li>WF-02: approve transitions REVIEW → APPROVED with reviewer metadata</li>
 *   <li>WF-03: reject transitions REVIEW → DRAFT with rejection reason</li>
 *   <li>WF-04: approve on DRAFT status returns 400</li>
 *   <li>WF-05: submitReview on REVIEW status returns 400</li>
 *   <li>WF-06: reject on APPROVED status returns 400</li>
 *   <li>WF-07: reject without reason throws BusinessException</li>
 *   <li>WF-08: updateStatus allows admin to set arbitrary valid status</li>
 *   <li>WF-09: updateStatus with invalid status throws BusinessException</li>
 * </ul>
 */
@Slf4j
@DisplayName("I18n Translation Workflow Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class I18nWorkflowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private I18nResourceService i18nResourceService;

    private static final String UNIQUE_PREFIX = "wf-test-" + System.currentTimeMillis();

    /**
     * Helper to create a DRAFT resource for workflow tests.
     */
    private I18nResource createDraftResource(String suffix) {
        I18nResource resource = I18nResource.builder()
            .i18nKey(UNIQUE_PREFIX + "." + suffix)
            .lang("ja-JP")
            .value("テスト値" + suffix)
            .source(I18nResource.SOURCE_IMPORT)
            .status(I18nResource.STATUS_DRAFT)
            .build();
        return i18nResourceService.create(resource);
    }

    /**
     * Helper to create a resource in REVIEW status for workflow tests.
     */
    private I18nResource createReviewResource(String suffix) {
        I18nResource draft = createDraftResource(suffix);
        return i18nResourceService.submitReview(draft.getPid());
    }

    // ==================== WF-01: submitReview ====================

    @Test
    @Order(1)
    @DisplayName("WF-01: submitReview transitions DRAFT → REVIEW")
    void submitReview_fromDraft_transitionsToReview() {
        I18nResource draft = createDraftResource("wf01");
        assertThat(draft.getStatus()).isEqualTo(I18nResource.STATUS_DRAFT);

        I18nResource reviewed = i18nResourceService.submitReview(draft.getPid());

        assertThat(reviewed.getStatus()).isEqualTo(I18nResource.STATUS_REVIEW);
        assertThat(reviewed.getPid()).isEqualTo(draft.getPid());
        assertThat(reviewed.getI18nKey()).isEqualTo(draft.getI18nKey());

        // Verify persisted in DB
        I18nResource persisted = i18nResourceService.findByPid(draft.getPid());
        assertThat(persisted.getStatus()).isEqualTo(I18nResource.STATUS_REVIEW);

        // Cleanup
        i18nResourceService.delete(draft.getPid());
    }

    // ==================== WF-02: approve ====================

    @Test
    @Order(2)
    @DisplayName("WF-02: approve transitions REVIEW → APPROVED with reviewer metadata")
    void approve_fromReview_transitionsToApproved() {
        I18nResource review = createReviewResource("wf02");
        assertThat(review.getStatus()).isEqualTo(I18nResource.STATUS_REVIEW);

        I18nResource approved = i18nResourceService.approve(review.getPid());

        assertThat(approved.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);
        assertThat(approved.getReviewedAt()).isNotNull();
        // reviewed_by may be null in test context (no auth user), but field should be set if user exists
        assertThat(approved.getRejectReason()).isNull();

        // Verify persisted
        I18nResource persisted = i18nResourceService.findByPid(review.getPid());
        assertThat(persisted.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);
        assertThat(persisted.getReviewedAt()).isNotNull();

        // Cleanup
        i18nResourceService.delete(review.getPid());
    }

    // ==================== WF-03: reject ====================

    @Test
    @Order(3)
    @DisplayName("WF-03: reject transitions REVIEW → DRAFT with rejection reason")
    void reject_fromReview_transitionsToDraftWithReason() {
        I18nResource review = createReviewResource("wf03");

        String reason = "Translation is inaccurate, please revise";
        I18nResource rejected = i18nResourceService.reject(review.getPid(), reason);

        assertThat(rejected.getStatus()).isEqualTo(I18nResource.STATUS_DRAFT);
        assertThat(rejected.getRejectReason()).isEqualTo(reason);
        assertThat(rejected.getReviewedAt()).isNotNull();

        // Verify persisted
        I18nResource persisted = i18nResourceService.findByPid(review.getPid());
        assertThat(persisted.getStatus()).isEqualTo(I18nResource.STATUS_DRAFT);
        assertThat(persisted.getRejectReason()).isEqualTo(reason);

        // Cleanup
        i18nResourceService.delete(review.getPid());
    }

    // ==================== WF-04: approve from wrong status ====================

    @Test
    @Order(4)
    @DisplayName("WF-04: approve on DRAFT status returns 400 (invalid transition)")
    void approve_fromDraft_returns400() {
        I18nResource draft = createDraftResource("wf04");

        assertThatThrownBy(() -> i18nResourceService.approve(draft.getPid()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("draft");

        // Cleanup
        i18nResourceService.delete(draft.getPid());
    }

    // ==================== WF-05: submitReview from wrong status ====================

    @Test
    @Order(5)
    @DisplayName("WF-05: submitReview on REVIEW status returns 400 (already in review)")
    void submitReview_fromReview_returns400() {
        I18nResource review = createReviewResource("wf05");

        assertThatThrownBy(() -> i18nResourceService.submitReview(review.getPid()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("review");

        // Cleanup
        i18nResourceService.delete(review.getPid());
    }

    // ==================== WF-06: reject from wrong status ====================

    @Test
    @Order(6)
    @DisplayName("WF-06: reject on APPROVED status returns 400 (invalid transition)")
    void reject_fromApproved_returns400() {
        I18nResource review = createReviewResource("wf06");
        I18nResource approved = i18nResourceService.approve(review.getPid());
        assertThat(approved.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);

        assertThatThrownBy(() -> i18nResourceService.reject(approved.getPid(), "reason"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("approved");

        // Cleanup
        i18nResourceService.delete(review.getPid());
    }

    // ==================== WF-07: reject without reason ====================

    @Test
    @Order(7)
    @DisplayName("WF-07: reject without reason throws BusinessException")
    void reject_withoutReason_throwsBusinessException() {
        I18nResource review = createReviewResource("wf07");

        assertThatThrownBy(() -> i18nResourceService.reject(review.getPid(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");

        assertThatThrownBy(() -> i18nResourceService.reject(review.getPid(), "   "))
            .isInstanceOf(BusinessException.class);

        // Cleanup
        i18nResourceService.delete(review.getPid());
    }

    // ==================== WF-08: updateStatus admin override ====================

    @Test
    @Order(8)
    @DisplayName("WF-08: updateStatus allows admin to set arbitrary valid status")
    void updateStatus_adminOverride_succeeds() {
        I18nResource draft = createDraftResource("wf08");

        I18nResource updated = i18nResourceService.updateStatus(draft.getPid(), I18nResource.STATUS_APPROVED);
        assertThat(updated.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);

        I18nResource persisted = i18nResourceService.findByPid(draft.getPid());
        assertThat(persisted.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);

        // Can also set to DEPRECATED
        i18nResourceService.updateStatus(draft.getPid(), I18nResource.STATUS_DEPRECATED);
        I18nResource deprecated = i18nResourceService.findByPid(draft.getPid());
        assertThat(deprecated.getStatus()).isEqualTo(I18nResource.STATUS_DEPRECATED);

        // Cleanup
        i18nResourceService.delete(draft.getPid());
    }

    // ==================== WF-09: updateStatus invalid status ====================

    @Test
    @Order(9)
    @DisplayName("WF-09: updateStatus with invalid status throws BusinessException")
    void updateStatus_invalidStatus_throwsBusinessException() {
        I18nResource draft = createDraftResource("wf09");

        assertThatThrownBy(() -> i18nResourceService.updateStatus(draft.getPid(), "invalid_status"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid status");

        // Cleanup
        i18nResourceService.delete(draft.getPid());
    }
}
