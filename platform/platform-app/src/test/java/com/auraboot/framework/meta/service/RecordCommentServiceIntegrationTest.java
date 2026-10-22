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

    // Intentionally unregistered: this suite exercises the polymorphic comment store itself.
    // "test_model" is now a real plugin fixture and therefore triggers row-ACL lookup.
    private static final String MODEL_CODE = "record_comment_test_target";
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

    @Test
    @Order(9)
    @DisplayName("CMT-09: page roots with normalized two-level replies and cascade root deletion")
    @SuppressWarnings("unchecked")
    void threadedRepliesPageAndCascade() {
        Map<String, Object> root = commentService.addInteractiveComment(
                MODEL_CODE, RECORD_PID, "Root discussion", List.of(), null);
        String rootPid = (String) root.get("commentPid");
        Map<String, Object> firstReply = commentService.addInteractiveComment(
                MODEL_CODE, RECORD_PID, "First reply", List.of(), rootPid);
        commentService.addInteractiveComment(
                MODEL_CODE, RECORD_PID, "Reply to reply", List.of(),
                (String) firstReply.get("commentPid"));

        Map<String, Object> page = commentService.pageComments(MODEL_CODE, RECORD_PID, 1, 10);
        assertThat(page.get("commentCount")).isEqualTo(3L);
        List<Map<String, Object>> roots = (List<Map<String, Object>>) page.get("items");
        Map<String, Object> pagedRoot = roots.stream()
                .filter(row -> rootPid.equals(row.get("commentPid")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> replies = (List<Map<String, Object>>) pagedRoot.get("replies");

        assertThat(replies).extracting(row -> row.get("content"))
                .containsExactly("First reply", "Reply to reply");
        assertThat(replies).allSatisfy(reply -> assertThat(reply.get("parentPid")).isEqualTo(rootPid));

        commentService.deleteComment(rootPid);
        assertThat(commentService.listComments(MODEL_CODE, RECORD_PID))
                .noneMatch(row -> rootPid.equals(row.get("commentPid"))
                        || rootPid.equals(row.get("parentPid")));
    }

    @Test
    @Order(10)
    @DisplayName("CMT-10: mention plus reply target is deduplicated to one tenant-scoped notification")
    void mentionReplyNotificationDeduplicated() {
        long otherUserId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000000 FROM ab_user", Long.class);
        long memberId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000000 FROM ab_tenant_member", Long.class);
        String otherUserPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_user (id, pid, user_name, nick_name, email, deleted_flag) VALUES (?, ?, ?, ?, ?, false)",
                otherUserId, otherUserPid, "comment-peer-" + otherUserPid,
                "Comment Peer", otherUserPid + "@example.test");
        jdbcTemplate.update(
                "INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag)"
                        + " VALUES (?, ?, ?, ?, 'active', false)",
                memberId, UniqueIdGenerator.generate(), testTenant.getId(), otherUserId);

        MetaContext.setContext(testTenant.getId(), otherUserId, otherUserPid, "comment-peer");
        String rootPid;
        try {
            rootPid = (String) commentService.addInteractiveComment(
                    MODEL_CODE, RECORD_PID, "Peer root", List.of(), null).get("commentPid");
        } finally {
            applyTestMetaContext();
        }

        commentService.addInteractiveComment(
                MODEL_CODE, RECORD_PID, "@Comment Peer please review", List.of(otherUserPid), rootPid);

        Integer notifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_notification"
                        + " WHERE tenant_id = ? AND user_id = ? AND source_type = ? AND source_id = ?",
                Integer.class, testTenant.getId(), otherUserId, MODEL_CODE, RECORD_PID);
        assertThat(notifications).isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("CMT-11: interactive mutation cannot reuse a comment pid through another record path")
    void interactiveMutationRequiresMatchingThreadPath() {
        String commentPid = (String) commentService.addInteractiveComment(
                MODEL_CODE, RECORD_PID, "Path-bound comment", List.of(), null).get("commentPid");

        assertThatThrownBy(() -> commentService.editInteractiveComment(
                MODEL_CODE, "different-record", commentPid, "Hijacked path", List.of()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> commentService.deleteInteractiveComment(
                MODEL_CODE, "different-record", commentPid))
                .isInstanceOf(RuntimeException.class);

        assertThat(commentService.listComments(MODEL_CODE, RECORD_PID))
                .anyMatch(row -> commentPid.equals(row.get("commentPid"))
                        && "Path-bound comment".equals(row.get("content")));
    }
}
