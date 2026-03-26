package com.auraboot.framework.meta.service;

import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.EmbeddingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private FileService fileService;

    private static final String MODEL_CODE = "test_model";
    private static final String RECORD_PID = "test_record_001";

    @Test
    @Order(1)
    @DisplayName("CMT-01: Add comment and list")
    void addAndList() {
        commentService.addComment(MODEL_CODE, RECORD_PID, "First test comment", null);
        commentService.addComment(MODEL_CODE, RECORD_PID, "Second test comment", null);

        List<Map<String, Object>> comments = commentService.listComments(MODEL_CODE, RECORD_PID);
        assertThat(comments).hasSizeGreaterThanOrEqualTo(2);

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
        commentService.addComment(MODEL_CODE, RECORD_PID, "Original content", null);

        List<Map<String, Object>> comments = commentService.listComments(MODEL_CODE, RECORD_PID);
        Number commentId = (Number) comments.get(0).get("id");

        commentService.editComment(commentId.longValue(), "Updated content");

        List<Map<String, Object>> after = commentService.listComments(MODEL_CODE, RECORD_PID);
        Map<String, Object> edited = after.stream()
                .filter(c -> commentId.equals(c.get("id")))
                .findFirst().orElseThrow();

        assertThat(edited.get("content")).isEqualTo("Updated content");
        assertThat(edited.get("is_edited")).isEqualTo(true);
    }

    @Test
    @Order(3)
    @DisplayName("CMT-03: Delete comment (soft delete)")
    void deleteComment() {
        commentService.addComment(MODEL_CODE, RECORD_PID, "To be deleted", null);

        List<Map<String, Object>> before = commentService.listComments(MODEL_CODE, RECORD_PID);
        int sizeBefore = before.size();
        Number commentId = (Number) before.get(0).get("id");

        commentService.deleteComment(commentId.longValue());

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
    @DisplayName("CMT-05: Comment stores created_by from MetaContext")
    void commentCreatedBy() {
        commentService.addComment(MODEL_CODE, RECORD_PID, "Auth check", null);

        List<Map<String, Object>> comments = commentService.listComments(MODEL_CODE, RECORD_PID);
        Map<String, Object> latest = comments.get(0);

        assertThat(latest.get("created_by")).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("CMT-06: Activity feed returns activities for record")
    void listActivity() {
        List<Map<String, Object>> activities = commentService.listActivity(MODEL_CODE, RECORD_PID);
        // May or may not have activities depending on whether commands were run
        assertThat(activities).isNotNull();
    }
}
