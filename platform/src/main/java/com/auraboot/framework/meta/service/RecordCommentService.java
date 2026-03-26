package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Record Comment Service (GAP-123)
 * Manages comments for dynamic entity records.
 * Uses JdbcTemplate for direct SQL (bypasses DynamicDataMapper SELECT-only restriction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordCommentService {

    private final JdbcTemplate jdbcTemplate;

    private static final String TABLE = "ab_record_comment";

    public List<Map<String, Object>> listComments(String modelCode, String recordPid) {
        return jdbcTemplate.queryForList(
                "SELECT id, model_code, record_pid, content, mentions, created_by, created_at, updated_at, is_edited "
                + "FROM " + TABLE
                + " WHERE model_code = ? AND record_pid = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)"
                + " ORDER BY created_at DESC",
                modelCode, recordPid);
    }

    public Map<String, Object> addComment(String modelCode, String recordPid, String content, String mentions) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "INSERT INTO " + TABLE
                + " (tenant_id, model_code, record_pid, content, mentions, created_by, created_at, updated_at, is_edited, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), false, false) "
                + "RETURNING id, model_code, record_pid, content, mentions, created_by, created_at",
                tenantId, modelCode, recordPid, content, mentions, userId);

        if (result.isEmpty()) throw new RuntimeException("Failed to insert comment");
        log.info("Comment added to {}/{} by user {}", modelCode, recordPid, userId);
        return result.get(0);
    }

    public Map<String, Object> editComment(Long commentId, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }

        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "UPDATE " + TABLE
                + " SET content = ?, updated_at = NOW(), is_edited = true"
                + " WHERE id = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)"
                + " RETURNING id, content, updated_at, is_edited",
                content, commentId);

        if (result.isEmpty()) throw new RuntimeException("Comment not found: " + commentId);
        return result.get(0);
    }

    public void deleteComment(Long commentId) {
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET deleted_flag = true WHERE id = ?", commentId);
        log.info("Comment {} deleted", commentId);
    }

    public List<Map<String, Object>> listActivity(String modelCode, String recordPid) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, object_model, object_record, activity_type, subject, actor_name, occurred_at "
                    + "FROM ab_activity"
                    + " WHERE object_model = ? AND object_record = ?"
                    + " ORDER BY occurred_at DESC LIMIT 50",
                    modelCode, recordPid);
        } catch (Exception e) {
            log.debug("Activity query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
