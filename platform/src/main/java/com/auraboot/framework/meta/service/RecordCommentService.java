package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final MetaModelService metaModelService;
    private final DynamicDataService dynamicDataService;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    private static final String TABLE = "ab_record_comment";
    private static final int MAX_CONTENT_LENGTH = 3_000;
    private static final int MAX_MENTIONS = 100;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Enforce record-level visibility before exposing / mutating a record's comment thread.
     *
     * <p>Comments are keyed by {@code (modelCode, recordPid)}. When {@code modelCode} is a
     * registered model, delegate to {@link DynamicDataService#getById} which applies the
     * caller's row-ACL / field-mask and throws {@code Access denied} when the caller cannot
     * view the record — this closes the intra-tenant bypass where a user without data
     * permission on a record could still read or append its comments (SEC-20260723-04).
     * Comments attached to non-model targets (arbitrary {@code modelCode}) are not row-ACL
     * controlled and are left unchanged.
     */
    private VisibleRecord assertRecordVisible(String modelCode, String recordPid) {
        if (modelCode == null || recordPid == null) {
            return VisibleRecord.unregistered();
        }
        Optional<ModelDefinition> registered = metaModelService.getModelDefinition(modelCode);
        if (registered.isEmpty()) return VisibleRecord.unregistered();
        ModelDefinition definition = registered.orElseThrow();
        return new VisibleRecord(definition, dynamicDataService.getById(modelCode, recordPid));
    }

    public List<Map<String, Object>> listComments(String modelCode, String recordPid) {
        assertRecordVisible(modelCode, recordPid);
        Long tenantId = MetaContext.getCurrentTenantId();
        return enrichComments(queryComments(
                "c.tenant_id = ? AND c.model_code = ? AND c.record_pid = ?", "c.created_at DESC, c.id DESC",
                tenantId, modelCode, recordPid), tenantId);
    }

    /**
     * Return a Cordys-compatible two-level thread: paged roots with all direct replies.
     * Legacy {@link #listComments(String, String)} remains available for automation callers.
     */
    public Map<String, Object> pageComments(String modelCode, String recordPid, int page, int size) {
        assertRecordVisible(modelCode, recordPid);
        Long tenantId = MetaContext.getCurrentTenantId();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE
                        + " WHERE tenant_id = ? AND model_code = ? AND record_pid = ?"
                        + " AND parent_pid IS NULL AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                Long.class, tenantId, modelCode, recordPid);
        Long commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE
                        + " WHERE tenant_id = ? AND model_code = ? AND record_pid = ?"
                        + " AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                Long.class, tenantId, modelCode, recordPid);
        List<Map<String, Object>> roots = enrichComments(jdbcTemplate.queryForList(
                commentSelect()
                        + " WHERE c.tenant_id = ? AND c.model_code = ? AND c.record_pid = ?"
                        + " AND c.parent_pid IS NULL"
                        + " AND (c.deleted_flag = FALSE OR c.deleted_flag IS NULL)"
                        + " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?",
                tenantId, modelCode, recordPid, safeSize, (safePage - 1) * safeSize), tenantId);

        if (!roots.isEmpty()) {
            List<String> rootPids = roots.stream().map(row -> String.valueOf(row.get("commentPid"))).toList();
            String placeholders = String.join(",", Collections.nCopies(rootPids.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(tenantId);
            args.addAll(rootPids);
            List<Map<String, Object>> replies = enrichComments(jdbcTemplate.queryForList(
                    commentSelect() + " WHERE c.tenant_id = ? AND c.parent_pid IN (" + placeholders + ")"
                            + " AND (c.deleted_flag = FALSE OR c.deleted_flag IS NULL)"
                            + " ORDER BY c.created_at ASC, c.id ASC",
                    args.toArray()), tenantId);
            Map<String, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
            for (Map<String, Object> reply : replies) {
                byParent.computeIfAbsent(String.valueOf(reply.get("parentPid")), ignored -> new ArrayList<>())
                        .add(reply);
            }
            roots.forEach(root -> root.put("replies",
                    byParent.getOrDefault(String.valueOf(root.get("commentPid")), List.of())));
        }

        long totalValue = total == null ? 0 : total;
        return new LinkedHashMap<>(Map.of(
                "items", roots,
                "total", totalValue,
                "commentCount", commentCount == null ? 0 : commentCount,
                "page", safePage,
                "size", safeSize,
                "hasMore", (long) safePage * safeSize < totalValue));
    }

    /** Backward-compatible entry used by automation and event-policy actions. */
    @Transactional
    public Map<String, Object> addComment(String modelCode, String recordPid, String content, String mentions) {
        return insertComment(modelCode, recordPid, content, parseLegacyMentions(mentions), mentions, null, false);
    }

    /** Interactive UI entry: supports replies, validated user mentions, and notifications. */
    @Transactional
    public Map<String, Object> addInteractiveComment(String modelCode, String recordPid, String content,
                                                      List<String> mentionUserPids, String requestedParentPid) {
        return insertComment(modelCode, recordPid, content, mentionUserPids, null, requestedParentPid, true);
    }

    private Map<String, Object> insertComment(String modelCode, String recordPid, String content,
                                              List<String> mentionUserPids, String legacyMentions,
                                              String requestedParentPid,
                                              boolean notifyRecipients) {
        String normalizedContent = validateContent(content);
        VisibleRecord visibleRecord = assertRecordVisible(modelCode, recordPid);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        List<String> mentions = notifyRecipients
                ? validateMentionPids(tenantId, mentionUserPids)
                : List.copyOf(mentionUserPids);
        ReplyTarget replyTarget = resolveReplyTarget(
                tenantId, modelCode, recordPid, requestedParentPid);
        String commentPid = UniqueIdGenerator.generate();
        String actorName = resolveActorName(userId);
        // Automation/event-policy callers historically persist opaque targets
        // such as ROLE:wd_manager. Interactive comments use validated user-PID
        // JSON, but the legacy non-notifying path must not silently erase its
        // established expression contract.
        String mentionsJson = legacyMentions != null && !legacyMentions.isBlank()
                ? legacyMentions
                : writeMentionPids(mentions);

        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "INSERT INTO " + TABLE
                + " (pid, tenant_id, model_code, record_pid, parent_pid, reply_to_user_pid, content, mentions,"
                + " created_by, created_at, updated_at, is_edited, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), false, false) "
                + "RETURNING pid AS \"commentPid\", model_code, record_pid, parent_pid AS \"parentPid\","
                + " reply_to_user_pid AS \"replyToUserPid\", content, mentions, created_at, updated_at, is_edited",
                commentPid, tenantId, modelCode, recordPid, replyTarget.rootCommentPid(),
                replyTarget.replyToUserPid(), normalizedContent, mentionsJson, String.valueOf(userId));

        if (result.isEmpty()) throw new RuntimeException("Failed to insert comment");
        log.info("Comment added to {}/{} by user {}", modelCode, recordPid, userId);
        Map<String, Object> row = new LinkedHashMap<>(result.get(0));
        row.put("actorName", actorName);
        row.put("canEdit", true);
        row.put("mentionedUsers", resolveMentionUsers(tenantId, mentions));
        if (notifyRecipients) {
            notifyCommentRecipients(modelCode, recordPid, normalizedContent, actorName, mentions,
                    replyTarget.replyToUserPid(), visibleRecord, false);
        }
        return row;
    }

    @Transactional
    public Map<String, Object> editComment(String commentPid, String content) {
        return editInteractiveComment(null, null, commentPid, content, null, false);
    }

    @Transactional
    public Map<String, Object> editInteractiveComment(String commentPid, String content,
                                                       List<String> mentionUserPids) {
        return editInteractiveComment(null, null, commentPid, content, mentionUserPids, true);
    }

    @Transactional
    public Map<String, Object> editInteractiveComment(String modelCode, String recordPid,
                                                       String commentPid, String content,
                                                       List<String> mentionUserPids) {
        assertRecordVisible(modelCode, recordPid);
        return editInteractiveComment(modelCode, recordPid, commentPid, content, mentionUserPids, true);
    }

    private Map<String, Object> editInteractiveComment(String modelCode, String recordPid,
                                                        String commentPid, String content,
                                                        List<String> mentionUserPids,
                                                        boolean notifyRecipients) {
        String normalizedContent = validateContent(content);
        if (commentPid == null || commentPid.isBlank()) {
            throw new IllegalArgumentException("Invalid comment pid");
        }

        // Only the author may edit, scoped to their own tenant. JdbcTemplate bypasses the MyBatis
        // tenant interceptor, so without explicit tenant_id + created_by any authenticated user
        // could edit any comment by reference (IDOR). created_by is a varchar column, so bind
        // the user id as a String.
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        List<String> mentions = mentionUserPids == null
                ? null : validateMentionPids(tenantId, mentionUserPids);
        String mentionSql = mentions == null ? "" : ", mentions = ?";
        List<Object> args = new ArrayList<>();
        args.add(normalizedContent);
        if (mentions != null) args.add(writeMentionPids(mentions));
        args.add(commentPid);
        args.add(tenantId);
        args.add(String.valueOf(userId));
        String threadSql = modelCode == null ? "" : " AND model_code = ? AND record_pid = ?";
        if (modelCode != null) {
            args.add(modelCode);
            args.add(recordPid);
        }

        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "UPDATE " + TABLE
                + " SET content = ?" + mentionSql + ", updated_at = NOW(), is_edited = true"
                + " WHERE pid = ? AND tenant_id = ? AND created_by = ?"
                + threadSql
                + " AND (deleted_flag = FALSE OR deleted_flag IS NULL)"
                + " RETURNING pid AS \"commentPid\", model_code, record_pid, parent_pid AS \"parentPid\","
                + " reply_to_user_pid AS \"replyToUserPid\", content, mentions, updated_at, is_edited",
                args.toArray());

        if (result.isEmpty()) {
            throw new RuntimeException("Comment not found or not owned by current user: " + commentPid);
        }
        Map<String, Object> row = new LinkedHashMap<>(result.get(0));
        row.put("canEdit", true);
        if (mentions != null) {
            row.put("mentionedUsers", resolveMentionUsers(tenantId, mentions));
            if (notifyRecipients) {
                String effectiveModelCode = String.valueOf(row.get("model_code"));
                String effectiveRecordPid = String.valueOf(row.get("record_pid"));
                notifyCommentRecipients(effectiveModelCode, effectiveRecordPid,
                        normalizedContent, resolveActorName(userId),
                        mentions, nullableString(row.get("replyToUserPid")), VisibleRecord.unregistered(), true);
            }
        }
        return row;
    }

    @Transactional
    public void deleteComment(String commentPid) {
        deleteComment(null, null, commentPid);
    }

    @Transactional
    public void deleteInteractiveComment(String modelCode, String recordPid, String commentPid) {
        assertRecordVisible(modelCode, recordPid);
        deleteComment(modelCode, recordPid, commentPid);
    }

    private void deleteComment(String modelCode, String recordPid, String commentPid) {
        if (commentPid == null || commentPid.isBlank()) {
            throw new IllegalArgumentException("Invalid comment pid");
        }
        // Author + tenant scoped (JdbcTemplate bypasses the tenant interceptor — see editComment).
        // created_by is a varchar column, so bind the user id as a String.
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String threadSql = modelCode == null ? "" : " AND model_code = ? AND record_pid = ?";
        List<Object> lookupArgs = new ArrayList<>(List.of(commentPid, tenantId, String.valueOf(userId)));
        if (modelCode != null) {
            lookupArgs.add(modelCode);
            lookupArgs.add(recordPid);
        }
        List<Map<String, Object>> owned = jdbcTemplate.queryForList(
                "SELECT pid, parent_pid FROM " + TABLE
                        + " WHERE pid = ? AND tenant_id = ? AND created_by = ?"
                        + threadSql
                        + " AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                lookupArgs.toArray());
        if (owned.isEmpty()) {
            throw new RuntimeException("Comment not found or not owned by current user: " + commentPid);
        }
        boolean rootComment = owned.get(0).get("parent_pid") == null;
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET deleted_flag = true, updated_at = NOW()"
                        + " WHERE tenant_id = ? AND (pid = ?" + (rootComment ? " OR parent_pid = ?" : "") + ")",
                rootComment ? new Object[]{tenantId, commentPid, commentPid}
                        : new Object[]{tenantId, commentPid});
        log.info("Comment {} deleted by user {}", commentPid, userId);
    }

    public List<Map<String, Object>> listActivity(String modelCode, String recordPid) {
        try {
            // Deny (return empty) when the caller cannot view the underlying record.
            assertRecordVisible(modelCode, recordPid);
            Long tenantId = MetaContext.getCurrentTenantId();
            return jdbcTemplate.queryForList(
                    "SELECT pid AS \"activityPid\", object_model, object_record, activity_type, subject, actor_name AS \"actorName\", occurred_at "
                    + "FROM ab_activity"
                    + " WHERE tenant_id = ? AND object_model = ? AND object_record = ?"
                    + " ORDER BY occurred_at DESC LIMIT 50",
                    tenantId, modelCode, recordPid);
        } catch (Exception e) {
            log.debug("Activity query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> queryComments(String where, String order, Object... args) {
        return jdbcTemplate.queryForList(commentSelect() + " WHERE " + where
                + " AND (c.deleted_flag = FALSE OR c.deleted_flag IS NULL) ORDER BY " + order, args);
    }

    private String commentSelect() {
        return "SELECT c.pid AS \"commentPid\", c.model_code, c.record_pid,"
                + " c.parent_pid AS \"parentPid\", c.reply_to_user_pid AS \"replyToUserPid\","
                + " c.content, c.mentions,"
                + " COALESCE(NULLIF(u.nick_name, ''), NULLIF(u.user_name, ''), u.email, 'User') AS \"actorName\","
                + " u.pid AS \"authorPid\","
                + " COALESCE(NULLIF(ru.nick_name, ''), NULLIF(ru.user_name, ''), ru.email) AS \"replyToName\","
                + " c.created_at, c.updated_at, c.is_edited, c.created_by"
                + " FROM " + TABLE + " c"
                + " LEFT JOIN ab_user u ON u.id::text = c.created_by"
                + " LEFT JOIN ab_user ru ON ru.pid = c.reply_to_user_pid";
    }

    private List<Map<String, Object>> enrichComments(List<Map<String, Object>> rows, Long tenantId) {
        String currentUserId = String.valueOf(MetaContext.getCurrentUserId());
        List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
        for (Map<String, Object> raw : rows) {
            Map<String, Object> row = new LinkedHashMap<>(raw);
            row.put("canEdit", currentUserId.equals(String.valueOf(row.remove("created_by"))));
            List<String> mentionPids = parseMentionPids(nullableString(row.get("mentions")));
            row.put("mentionUserPids", mentionPids);
            row.put("mentionedUsers", resolveMentionUsers(tenantId, mentionPids));
            enriched.add(row);
        }
        return enriched;
    }

    private String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        String normalized = content.trim();
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Comment content cannot exceed " + MAX_CONTENT_LENGTH + " characters");
        }
        return normalized;
    }

    private List<String> validateMentionPids(Long tenantId, List<String> mentionUserPids) {
        if (mentionUserPids == null || mentionUserPids.isEmpty()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String pid : mentionUserPids) {
            if (pid != null && !pid.isBlank()) unique.add(pid.trim());
        }
        if (unique.size() > MAX_MENTIONS) {
            throw new IllegalArgumentException("A comment cannot mention more than " + MAX_MENTIONS + " users");
        }
        for (String pid : unique) {
            if (userMapper.findUserIdInTenantByPid(tenantId, pid) == null) {
                throw new IllegalArgumentException("Mentioned user is not active in the current tenant: " + pid);
            }
        }
        return List.copyOf(unique);
    }

    private List<String> parseLegacyMentions(String mentions) {
        return parseMentionPids(mentions);
    }

    private List<String> parseMentionPids(String mentions) {
        if (mentions == null || mentions.isBlank()) return List.of();
        try {
            List<String> parsed = objectMapper.readValue(mentions, new TypeReference<>() {});
            return parsed == null ? List.of() : parsed.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(MAX_MENTIONS)
                    .toList();
        } catch (Exception ignored) {
            // Historical automation rows sometimes stored display text (for example "@ops").
            // Keep them readable but do not treat unverified text as a user identity.
            return List.of();
        }
    }

    private String writeMentionPids(List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(mentions);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid comment mentions", e);
        }
    }

    private List<Map<String, Object>> resolveMentionUsers(Long tenantId, List<String> pids) {
        if (pids == null || pids.isEmpty()) return List.of();
        List<Map<String, Object>> users = new ArrayList<>();
        for (String pid : pids) {
            UserSearchDTO user = userService.findInTenantByPid(tenantId, pid);
            if (user == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pid", user.getPid());
            item.put("displayName", user.getDisplayName());
            item.put("email", user.getEmail());
            users.add(item);
        }
        return users;
    }

    private ReplyTarget resolveReplyTarget(Long tenantId, String modelCode, String recordPid,
                                           String requestedParentPid) {
        if (requestedParentPid == null || requestedParentPid.isBlank()) return ReplyTarget.root();
        List<Map<String, Object>> parents = jdbcTemplate.queryForList(
                "SELECT c.pid, c.parent_pid, u.pid AS author_pid FROM " + TABLE + " c"
                        + " LEFT JOIN ab_user u ON u.id::text = c.created_by"
                        + " WHERE c.pid = ? AND c.tenant_id = ? AND c.model_code = ? AND c.record_pid = ?"
                        + " AND (c.deleted_flag = FALSE OR c.deleted_flag IS NULL)",
                requestedParentPid, tenantId, modelCode, recordPid);
        if (parents.isEmpty()) {
            throw new IllegalArgumentException("Reply target is not part of this comment thread");
        }
        Map<String, Object> target = parents.get(0);
        String rootPid = nullableString(target.get("parent_pid"));
        if (rootPid == null) rootPid = nullableString(target.get("pid"));
        return new ReplyTarget(rootPid, nullableString(target.get("author_pid")));
    }

    private void notifyCommentRecipients(String modelCode, String recordPid, String content,
                                         String actorName, List<String> mentionPids,
                                         String replyToUserPid, VisibleRecord visibleRecord,
                                         boolean edit) {
        LinkedHashMap<String, NotificationReason> recipients = new LinkedHashMap<>();
        for (String mentionPid : mentionPids) {
            recipients.put(mentionPid, NotificationReason.MENTION);
        }
        if (replyToUserPid != null && !replyToUserPid.isBlank()) {
            recipients.putIfAbsent(replyToUserPid, NotificationReason.REPLY);
        }
        if (!edit) {
            String ownerPid = resolveOwnerPid(visibleRecord);
            if (ownerPid != null && !ownerPid.isBlank()) {
                recipients.putIfAbsent(ownerPid, NotificationReason.OWNER);
            }
        }
        recipients.remove(MetaContext.getCurrentUserPid());

        String preview = content.length() <= 160 ? content : content.substring(0, 157) + "...";
        for (Map.Entry<String, NotificationReason> recipient : recipients.entrySet()) {
            Long recipientUserId = userMapper.findUserIdInTenantByPid(
                    MetaContext.getCurrentTenantId(), recipient.getKey());
            if (recipientUserId == null) continue;
            String title = switch (recipient.getValue()) {
                case MENTION -> "你在评论中被 @ 提及";
                case REPLY -> "你的评论收到了回复";
                case OWNER -> "记录新增了一条评论";
            };
            notificationService.sendInApp(recipientUserId, title,
                    actorName + "：" + preview, "BUSINESS", modelCode, recordPid);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveOwnerPid(VisibleRecord visibleRecord) {
        if (visibleRecord.definition() == null || visibleRecord.data() == null) return null;
        Object rawDataScope = visibleRecord.definition().getExtension() == null
                ? null : visibleRecord.definition().getExtension().get("dataScope");
        if (!(rawDataScope instanceof Map<?, ?> dataScope)) return null;
        Object ownerField = dataScope.get("ownerField");
        if (ownerField == null || ownerField.toString().isBlank()) return null;
        return nullableString(visibleRecord.data().get(ownerField.toString()));
    }

    private static String nullableString(Object value) {
        if (value == null) return null;
        String normalized = value.toString().trim();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized) ? null : normalized;
    }

    private String resolveActorName(Long userId) {
        if (userId == null) {
            return "User";
        }
        try {
            String actorName = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email, 'User') "
                    + "FROM ab_user WHERE id = ?",
                    String.class,
                    userId);
            return Optional.ofNullable(actorName).filter(name -> !name.isBlank()).orElse("User");
        } catch (Exception e) {
            return Optional.ofNullable(MetaContext.getCurrentUsername())
                    .filter(name -> !name.isBlank())
                    .orElse("User");
        }
    }

    private record VisibleRecord(ModelDefinition definition, Map<String, Object> data) {
        private static VisibleRecord unregistered() {
            return new VisibleRecord(null, null);
        }
    }

    private record ReplyTarget(String rootCommentPid, String replyToUserPid) {
        private static ReplyTarget root() {
            return new ReplyTarget(null, null);
        }
    }

    private enum NotificationReason {
        MENTION,
        REPLY,
        OWNER
    }
}
