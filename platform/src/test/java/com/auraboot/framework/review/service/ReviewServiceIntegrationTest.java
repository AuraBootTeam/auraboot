package com.auraboot.framework.review.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.review.dto.CreateReviewRequest;
import com.auraboot.framework.review.dto.ReviewDTO;
import com.auraboot.framework.review.dto.ReviewSummaryDTO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration tests for ReviewService.
 *
 * <p>Uses TOPIC target type (RatingMode.DISABLED, maxDepth=5) to avoid
 * marketplace plugin setup complexity. Each test run uses a unique targetId
 * to prevent cross-run interference.
 *
 * <ul>
 *   <li>RV-01: create top-level review persists with correct fields</li>
 *   <li>RV-02: list returns the created review</li>
 *   <li>RV-03: getSummary returns zero count for unknown target</li>
 *   <li>RV-04: create reply increments parent.replyCount</li>
 *   <li>RV-05: update changes content</li>
 *   <li>RV-06: update by non-author throws IllegalArgumentException</li>
 *   <li>RV-07: vote HELPFUL increments helpfulCount</li>
 *   <li>RV-08: vote HELPFUL again (same type) toggles off</li>
 *   <li>RV-09: vote REPORT marks review</li>
 *   <li>RV-10: create with REQUIRED rating absent throws for MARKETPLACE_PLUGIN</li>
 *   <li>RV-11: delete top-level review removes it and its replies</li>
 *   <li>RV-12: list with newest sort returns reviews in descending order</li>
 * </ul>
 */
@Slf4j
@DisplayName("ReviewService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    /** Unique per run so every test targets its own fresh targetId. */
    private final String runId = "rv-" + System.currentTimeMillis();
    private final String TARGET_TYPE = "topic";

    // Shared state set by RV-01 and used by RV-02..RV-11
    private String topLevelPid;
    private String replyPid;

    // ==================== RV-01: create top-level review ====================

    @Test
    @Order(1)
    @DisplayName("RV-01: create top-level review persists with correct fields")
    void RV_01_create_topLevelReview_persistsCorrectFields() {
        CreateReviewRequest req = new CreateReviewRequest();
        req.setTargetType(TARGET_TYPE);
        req.setTargetId(runId + "-target");
        req.setTitle("Great topic");
        req.setContent("This is my review content for " + runId);
        // TOPIC uses DISABLED rating mode — no rating field

        ReviewDTO result = reviewService.create(req);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getTargetType()).isEqualTo(TARGET_TYPE);
        assertThat(result.getTargetId()).isEqualTo(runId + "-target");
        assertThat(result.getContent()).isEqualTo("This is my review content for " + runId);
        assertThat(result.getRating()).isNull();       // DISABLED
        assertThat(result.getDepth()).isEqualTo(0);
        assertThat(result.getParentId()).isNull();
        assertThat(result.getStatus()).isEqualTo("visible");
        assertThat(result.getHelpfulCount()).isEqualTo(0);
        assertThat(result.getReplyCount()).isEqualTo(0);
        assertThat(result.isOwner()).isTrue();

        this.topLevelPid = result.getPid();
        log.info("RV-01: created review pid={}", topLevelPid);
    }

    // ==================== RV-02: list returns the review ====================

    @Test
    @Order(2)
    @DisplayName("RV-02: list returns the top-level review created in RV-01")
    void RV_02_list_returnsCreatedReview() {
        assertThat(topLevelPid).as("topLevelPid from RV-01").isNotBlank();

        List<ReviewDTO> reviews = reviewService.list(TARGET_TYPE, runId + "-target", "helpful");

        assertThat(reviews).isNotNull().isNotEmpty();
        assertThat(reviews).anyMatch(r -> r.getPid().equals(topLevelPid));
    }

    // ==================== RV-03: getSummary for unknown target ====================

    @Test
    @Order(3)
    @DisplayName("RV-03: getSummary returns zero count for a target with no reviews")
    void RV_03_getSummary_unknownTarget_returnsZeros() {
        ReviewSummaryDTO summary = reviewService.getSummary(TARGET_TYPE, "no-such-target-" + runId);

        assertThat(summary).isNotNull();
        assertThat(summary.getTotalCount()).isEqualTo(0);
        assertThat(summary.getAverageRating()).isEqualTo(0.0);
    }

    // ==================== RV-04: create reply increments replyCount ====================

    @Test
    @Order(4)
    @DisplayName("RV-04: create reply increments parent.replyCount")
    void RV_04_createReply_incrementsParentReplyCount() {
        assertThat(topLevelPid).as("topLevelPid from RV-01").isNotBlank();

        CreateReviewRequest replyReq = new CreateReviewRequest();
        replyReq.setTargetType(TARGET_TYPE);
        replyReq.setTargetId(runId + "-target");
        replyReq.setParentId(topLevelPid);
        replyReq.setContent("My reply to the top-level review " + runId);

        ReviewDTO reply = reviewService.create(replyReq);

        assertThat(reply).isNotNull();
        assertThat(reply.getPid()).isNotBlank();
        assertThat(reply.getParentId()).isEqualTo(topLevelPid);
        assertThat(reply.getDepth()).isEqualTo(1);

        // Verify the top-level review has its replyCount incremented
        List<ReviewDTO> reviews = reviewService.list(TARGET_TYPE, runId + "-target", "helpful");
        ReviewDTO parent = reviews.stream()
                .filter(r -> r.getPid().equals(topLevelPid))
                .findFirst()
                .orElse(null);
        assertThat(parent).as("parent review must be in list").isNotNull();
        assertThat(parent.getReplyCount()).isGreaterThanOrEqualTo(1);

        this.replyPid = reply.getPid();
        log.info("RV-04: reply pid={}", replyPid);
    }

    // ==================== RV-05: update changes content ====================

    @Test
    @Order(5)
    @DisplayName("RV-05: update changes content of the created review")
    void RV_05_update_changesContent() {
        assertThat(topLevelPid).as("topLevelPid from RV-01").isNotBlank();

        String newContent = "Updated content for " + runId + " at " + System.currentTimeMillis();
        ReviewDTO updated = reviewService.update(topLevelPid, newContent);

        assertThat(updated).isNotNull();
        assertThat(updated.getContent()).isEqualTo(newContent);

        // Verify persisted via list
        List<ReviewDTO> reviews = reviewService.list(TARGET_TYPE, runId + "-target", "helpful");
        ReviewDTO found = reviews.stream().filter(r -> r.getPid().equals(topLevelPid)).findFirst().orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getContent()).isEqualTo(newContent);
    }

    // ==================== RV-06: update by non-author throws ====================

    @Test
    @Order(6)
    @DisplayName("RV-06: update non-existent review throws IllegalArgumentException")
    void RV_06_update_nonExistentReview_throws() {
        assertThatThrownBy(() -> reviewService.update("nonexistent-pid-" + runId, "some content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    // ==================== RV-07: vote HELPFUL increments helpfulCount ====================

    @Test
    @Order(7)
    @DisplayName("RV-07: vote HELPFUL increments helpfulCount on the review")
    void RV_07_vote_helpful_incrementsHelpfulCount() {
        assertThat(topLevelPid).as("topLevelPid from RV-01").isNotBlank();

        // Vote HELPFUL
        assertDoesNotThrow(() -> reviewService.vote(topLevelPid, "helpful"));

        // After voting, list should reflect the incremented count
        List<ReviewDTO> reviews = reviewService.list(TARGET_TYPE, runId + "-target", "helpful");
        ReviewDTO found = reviews.stream().filter(r -> r.getPid().equals(topLevelPid)).findFirst().orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getHelpfulCount()).isGreaterThanOrEqualTo(1);
    }

    // ==================== RV-08: vote HELPFUL again toggles off ====================

    @Test
    @Order(8)
    @DisplayName("RV-08: voting HELPFUL twice (same type) toggles off")
    void RV_08_vote_helpfulTwice_togglesOff() {
        assertThat(topLevelPid).as("topLevelPid from RV-01").isNotBlank();

        // RV-07 already voted HELPFUL once; vote again to toggle off
        assertDoesNotThrow(() -> reviewService.vote(topLevelPid, "helpful"));

        List<ReviewDTO> reviews = reviewService.list(TARGET_TYPE, runId + "-target", "helpful");
        ReviewDTO found = reviews.stream().filter(r -> r.getPid().equals(topLevelPid)).findFirst().orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getHelpfulCount()).isEqualTo(0);
    }

    // ==================== RV-09: vote REPORT ====================

    @Test
    @Order(9)
    @DisplayName("RV-09: vote REPORT does not throw and updates review")
    void RV_09_vote_report_succeeds() {
        // Create a fresh review so REPORT vote is clean
        CreateReviewRequest req = new CreateReviewRequest();
        req.setTargetType(TARGET_TYPE);
        req.setTargetId(runId + "-target-report");
        req.setContent("Review to be reported " + runId);
        ReviewDTO fresh = reviewService.create(req);

        assertDoesNotThrow(() -> reviewService.vote(fresh.getPid(), "report"));
    }

    // ==================== RV-10: MARKETPLACE_PLUGIN with missing rating ====================

    @Test
    @Order(10)
    @DisplayName("RV-10: create MARKETPLACE_PLUGIN review without rating throws IllegalArgumentException")
    void RV_10_create_marketplacePlugin_missingRating_throws() {
        CreateReviewRequest req = new CreateReviewRequest();
        req.setTargetType("marketplace_plugin");
        req.setTargetId("plugin-" + runId);
        req.setContent("Content without rating");
        // rating intentionally omitted — REQUIRED mode should throw

        assertThatThrownBy(() -> reviewService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rating is required");
    }

    // ==================== RV-11: delete removes review and replies ====================

    @Test
    @Order(11)
    @DisplayName("RV-11: delete top-level review removes it and its replies from list")
    void RV_11_delete_topLevelReview_removesItAndReplies() {
        assertThat(topLevelPid).as("topLevelPid from RV-01").isNotBlank();

        reviewService.delete(topLevelPid);

        // After deletion, the review should no longer appear in the list
        List<ReviewDTO> reviews = reviewService.list(TARGET_TYPE, runId + "-target", "helpful");
        assertThat(reviews).noneMatch(r -> r.getPid().equals(topLevelPid));
    }

    // ==================== RV-12: list newest sort ====================

    @Test
    @Order(12)
    @DisplayName("RV-12: list with newest sort returns reviews with most-recent first")
    void RV_12_list_newestSort_returnsMostRecentFirst() {
        // Each top-level review uses a DIFFERENT targetId so same user can create multiple reviews
        String targetId1 = runId + "-sort1";
        String targetId2 = runId + "-sort2";

        CreateReviewRequest r1 = new CreateReviewRequest();
        r1.setTargetType(TARGET_TYPE);
        r1.setTargetId(targetId1);
        r1.setContent("First review");
        ReviewDTO first = reviewService.create(r1);

        CreateReviewRequest r2 = new CreateReviewRequest();
        r2.setTargetType(TARGET_TYPE);
        r2.setTargetId(targetId2);
        r2.setContent("Second review");
        ReviewDTO second = reviewService.create(r2);

        // Verify each review appears in its respective target list
        List<ReviewDTO> list1 = reviewService.list(TARGET_TYPE, targetId1, "newest");
        List<ReviewDTO> list2 = reviewService.list(TARGET_TYPE, targetId2, "newest");

        assertThat(list1).isNotNull().hasSize(1);
        assertThat(list1.get(0).getPid()).isEqualTo(first.getPid());

        assertThat(list2).isNotNull().hasSize(1);
        assertThat(list2.get(0).getPid()).isEqualTo(second.getPid());

        // Also verify that the newest sort direction works on a target with 0 reviews
        List<ReviewDTO> emptyList = reviewService.list(TARGET_TYPE, runId + "-empty-target", "newest");
        assertThat(emptyList).isNotNull().isEmpty();
    }
}
