package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.EmbeddingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for RecordCommentService — CRUD comments on records.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecordCommentServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RecordCommentService commentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private FileService fileService;

    private static final String MODEL_CODE = "test_model";
    private static final String RECORD_PID = "test_record_001";

    @Test
    @Order(1)
    @DisplayName("CMT-01: Add comment and list")
    void addAndList() {
        Map<String, Object> created = commentService.addComment(MODEL_CODE, RECORD_PID, "First test comment", null);
        commentService.addComment(MODEL_CODE, RECORD_PID, "Second test comment", null);

        assertThat(created.get("commentPid")).isNotNull();
        assertThat((String) created.get("actorName")).isNotBlank();
        assertThat(created).doesNotContainKey("id");
        assertThat(created).doesNotContainKey("created_by");

        List<Map<String, Object>> comments = commentService.listComments(MODEL_CODE, RECORD_PID);
        assertThat(comments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(comments).allSatisfy(comment -> {
            assertThat(comment.get("commentPid")).isNotNull();
            assertThat(comment.get("actorName")).isNotNull();
            assertThat(comment).doesNotContainKey("id");
            assertThat(comment).doesNotContainKey("created_by");
        });

        // Both comments should be present (order may vary within same transaction)
        List<String> contents = comments.stream()
                .map(c -> (String) c.get("content"))
                .toList();
        assertThat(contents).contains("First test comment", "Second test comment");
    }

    @Test
    @Order(2)
    @DisplayName("CMT-02: Edit comment marks as edited")
    void editComment() {
        Map<String, Object> created = commentService.addComment(MODEL_CODE, RECORD_PID, "Original content", null);
        String commentPid = (String) created.get("commentPid");

        commentService.editComment(commentPid, "Updated content");

        List<Map<String, Object>> after = commentService.listComments(MODEL_CODE, RECORD_PID);
        Map<String, Object> edited = after.stream()
                .filter(c -> commentPid.equals(c.get("commentPid")))
                .findFirst().orElseThrow();

        assertThat(edited.get("content")).isEqualTo("Updated content");
        assertThat(edited.get("is_edited")).isEqualTo(true);
    }

    @Test
    @Order(3)
    @DisplayName("CMT-03: Delete comment (soft delete)")
    void deleteComment() {
        Map<String, Object> created = commentService.addComment(MODEL_CODE, RECORD_PID, "To be deleted", null);
        String commentPid = (String) created.get("commentPid");

        List<Map<String, Object>> before = commentService.listComments(MODEL_CODE, RECORD_PID);
        int sizeBefore = before.size();

        commentService.deleteComment(commentPid);

        List<Map<String, Object>> after = commentService.listComments(MODEL_CODE, RECORD_PID);
        assertThat(after).hasSize(sizeBefore - 1);
    }

    @Test
    @Order(4)
    @DisplayName("CMT-04: List comments for non-existent record returns empty")
    void listEmpty() {
        List<Map<String, Object>> comments = commentService.listComments("no_model", "no_record");
        assertThat(comments).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("CMT-05: Comment exposes display actor name without raw author id")
    void commentActorName() {
        Map<String, Object> created = commentService.addComment(MODEL_CODE, RECORD_PID, "Auth check", null);
        String commentPid = (String) created.get("commentPid");

        List<Map<String, Object>> comments = commentService.listComments(MODEL_CODE, RECORD_PID);
        Map<String, Object> latest = comments.stream()
                .filter(comment -> commentPid.equals(comment.get("commentPid")))
                .findFirst()
                .orElseThrow();

        assertThat((String) created.get("actorName")).isNotBlank();
        assertThat(latest.get("actorName")).isEqualTo(created.get("actorName"));
        assertThat(latest).doesNotContainKey("created_by");
        assertThat(latest).doesNotContainKey("id");
    }

    @Test
    @Order(6)
    @DisplayName("CMT-06: Activity feed returns activities for record")
    void listActivity() {
        jdbcTemplate.update(
                "INSERT INTO ab_activity (pid, tenant_id, object_model, object_record, activity_type, subject, actor_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UniqueIdGenerator.generate(), testTenant.getId(), MODEL_CODE, RECORD_PID, "NOTE",
                "Visible activity", "Test User");
        jdbcTemplate.update(
                "INSERT INTO ab_activity (pid, tenant_id, object_model, object_record, activity_type, subject, actor_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UniqueIdGenerator.generate(), testTenant.getId() + 999_999L, MODEL_CODE, RECORD_PID, "NOTE",
                "Other tenant activity", "Other User");

        List<Map<String, Object>> activities = commentService.listActivity(MODEL_CODE, RECORD_PID);
        assertThat(activities).isNotEmpty();
        assertThat(activities).allSatisfy(activity -> {
            assertThat(activity.get("activityPid")).isNotNull();
            assertThat(activity.get("actorName")).isNotNull();
            assertThat(activity).doesNotContainKey("id");
            assertThat(activity).doesNotContainKey("actor_id");
        });
        assertThat(activities.stream().map(activity -> activity.get("subject")).toList())
                .contains("Visible activity")
                .doesNotContain("Other tenant activity");
    }

    @Test
    @Order(7)
    @DisplayName("CMT-07: a different user cannot edit my comment (IDOR guard)")
    void editCommentForeignUserDenied() {
        Map<String, Object> created = commentService.addComment(MODEL_CODE, RECORD_PID, "Owner-only content", null);
        String commentPid = (String) created.get("commentPid");

        // Simulate a second authenticated user in the same tenant (not the author).
        Long foreignUserId = testUser.getId() + 999_999L;
        MetaContext.setContext(testTenant.getId(), foreignUserId, testUser.getPid(), "foreign-user");
        try {
            assertThatThrownBy(() -> commentService.editComment(commentPid, "hijacked"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            applyTestMetaContext();
        }

        // Content must be unchanged for the real owner.
        Map<String, Object> after = commentService.listComments(MODEL_CODE, RECORD_PID).stream()
                .filter(c -> commentPid.equals(c.get("commentPid"))).findFirst().orElseThrow();
        assertThat(after.get("content")).isEqualTo("Owner-only content");
    }

    @Test
    @Order(8)
    @DisplayName("CMT-08: a different user cannot delete my comment (IDOR guard)")
    void deleteCommentForeignUserDenied() {
        Map<String, Object> created = commentService.addComment(MODEL_CODE, RECORD_PID, "Keep me", null);
        String commentPid = (String) created.get("commentPid");

        Long foreignUserId = testUser.getId() + 888_888L;
        MetaContext.setContext(testTenant.getId(), foreignUserId, testUser.getPid(), "foreign-user");
        try {
            assertThatThrownBy(() -> commentService.deleteComment(commentPid))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            applyTestMetaContext();
        }

        // Comment must still be visible to the owner (not soft-deleted by the foreign user).
        boolean stillPresent = commentService.listComments(MODEL_CODE, RECORD_PID).stream()
                .anyMatch(c -> commentPid.equals(c.get("commentPid")));
        assertThat(stillPresent).isTrue();
    }
}
