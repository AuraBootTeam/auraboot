package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-control service for the Soul Profile (PR-76, plan §5.3).
 *
 * <p>Synchronous operations invoked by {@code UserSoulProfileController}
 * (Phase 4). Every edit is tenant-scoped: we reject any request that
 * addresses a row whose {@code tenant_id} does not match the caller's
 * tenant, and refuse to act on ARCHIVED profiles (GDPR-forgotten users).
 *
 * <p>Field-path examples: {@code persona},
 * {@code preferences.communication_style}, {@code boundaries},
 * {@code habits.recurring_actions}. Paths are treated as opaque keys —
 * the Reader (Phase 3) decides how to interpret them when building the
 * grounding section.
 *
 * <p>Mutations go through {@code edited_fields JSONB}:
 * <ul>
 *   <li>{@link #pin}: {@code {path: "locked"}}</li>
 *   <li>{@link #hide}: {@code {path: "hidden"}}</li>
 *   <li>{@link #edit}: {@code {path: {override_text, edited_at}}}</li>
 *   <li>{@link #reset}: removes key; {@code reset(null)} clears the map</li>
 *   <li>{@link #hideProfile}: sets {@code hidden_at} on the ACTIVE row</li>
 *   <li>{@link #forgetProfile}: cascades — archives all rows + inserts a
 *       tombstone that the deriver honours on subsequent ticks</li>
 * </ul>
 */
@Slf4j
@Service
public class UserSoulProfileEditor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_SUPERSEDED = "SUPERSEDED";
    static final String STATUS_ARCHIVED = "ARCHIVED";

    static final String FLAG_LOCKED = "locked";
    static final String FLAG_HIDDEN = "hidden";
    static final String TOMBSTONE_FORGOTTEN_KEY = "_forgotten";
    static final String EDIT_OVERRIDE_TEXT = "override_text";
    static final String EDIT_TIMESTAMP = "edited_at";

    private final JdbcTemplate jdbcTemplate;
    private final UserSoulProfileMetrics metrics;

    public UserSoulProfileEditor(JdbcTemplate jdbcTemplate,
                                 UserSoulProfileMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    public record EditResult(String pid, int version, Map<String, Object> editedFields,
                             String status) {}

    // ---- Field-level edits ----------------------------------------------

    @Transactional
    public EditResult pin(Long tenantId, String userId, String fieldPath) {
        requireFieldPath(fieldPath);
        EditResult r = updateEditedFields(tenantId, userId, (fields) -> fields.put(fieldPath, FLAG_LOCKED));
        metrics.recordUserEdit(tenantId, UserSoulProfileMetrics.EDIT_PIN);
        return r;
    }

    @Transactional
    public EditResult hide(Long tenantId, String userId, String fieldPath) {
        requireFieldPath(fieldPath);
        EditResult r = updateEditedFields(tenantId, userId, (fields) -> fields.put(fieldPath, FLAG_HIDDEN));
        metrics.recordUserEdit(tenantId, UserSoulProfileMetrics.EDIT_HIDE);
        return r;
    }

    @Transactional
    public EditResult edit(Long tenantId, String userId, String fieldPath, String newText) {
        requireFieldPath(fieldPath);
        if (newText == null) {
            throw new IllegalArgumentException("newText required");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(EDIT_OVERRIDE_TEXT, newText);
        entry.put(EDIT_TIMESTAMP, Instant.now().toString());
        EditResult r = updateEditedFields(tenantId, userId, (fields) -> fields.put(fieldPath, entry));
        metrics.recordUserEdit(tenantId, UserSoulProfileMetrics.EDIT_EDIT);
        return r;
    }

    @Transactional
    public EditResult reset(Long tenantId, String userId, String fieldPath) {
        EditResult r;
        if (fieldPath == null || fieldPath.isBlank()) {
            r = updateEditedFields(tenantId, userId, Map::clear);
        } else {
            r = updateEditedFields(tenantId, userId, (fields) -> fields.remove(fieldPath));
        }
        metrics.recordUserEdit(tenantId, UserSoulProfileMetrics.EDIT_RESET);
        return r;
    }

    // ---- Profile-wide operations ---------------------------------------

    /**
     * Soft-hide the user's profile. Reader (Phase 3) treats a hidden profile
     * as absent. Does not delete rows — reset via {@code edited_fields}
     * or {@code UPDATE ... SET hidden_at = NULL} would un-hide.
     */
    @Transactional
    public EditResult hideProfile(Long tenantId, String userId) {
        Map<String, Object> row = loadLiveRow(tenantId, userId);
        assertTenant(row, tenantId);
        String pid = (String) row.get("pid");
        int version = ((Number) row.get("version")).intValue();
        String status = (String) row.get("status");
        jdbcTemplate.update(
                "UPDATE ab_agent_user_soul_profile SET hidden_at = NOW() WHERE pid = ?", pid);
        metrics.recordUserEdit(tenantId, UserSoulProfileMetrics.EDIT_HIDE_PROFILE);
        return new EditResult(pid, version, readEditedFields(pid), status);
    }

    /**
     * GDPR forget cascade: archives all rows for the user and inserts a
     * tombstone marker that {@code UserSoulProfileDeriver} honours on
     * subsequent ticks. The tombstone carries
     * {@code edited_fields = {"_forgotten": true}} and an empty
     * {@code profile}.
     */
    @Transactional
    public EditResult forgetProfile(Long tenantId, String userId) {
        if (tenantId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId + userId required");
        }
        // Find max version across any status (including already-ARCHIVED) so
        // a repeated forget produces a fresh tombstone version.
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ?",
                Integer.class, tenantId, userId);
        if (maxVersion == null || maxVersion == 0) {
            throw new IllegalArgumentException(
                    "no soul profile rows found for tenant=" + tenantId + " user=" + userId);
        }

        // Archive every row for this (tenant, user).
        jdbcTemplate.update(
                "UPDATE ab_agent_user_soul_profile "
                        + "SET status = ?, hidden_at = NOW() "
                        + "WHERE tenant_id = ? AND user_id = ? AND status <> ?",
                STATUS_ARCHIVED, tenantId, userId, STATUS_ARCHIVED);

        // Insert tombstone.
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> tombstoneEdits = new LinkedHashMap<>();
        tombstoneEdits.put(TOMBSTONE_FORGOTTEN_KEY, true);
        jdbcTemplate.update(
                "INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " edited_fields, hidden_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, NOW(), NOW())",
                pid, tenantId, userId, maxVersion + 1, STATUS_ARCHIVED,
                "{}", "tombstone:" + pid, toJson(tombstoneEdits));

        metrics.recordUserEdit(tenantId, UserSoulProfileMetrics.EDIT_FORGET);
        log.info("UserSoulProfileEditor: forgetProfile tenant={} user={} tombstone_pid={}",
                tenantId, userId, pid);
        return new EditResult(pid, maxVersion + 1, tombstoneEdits, STATUS_ARCHIVED);
    }

    // ---- Introspection (for deriver) ------------------------------------

    /**
     * True when the user has a forget tombstone. Called by
     * {@code UserSoulProfileDeriver} to skip re-derivation.
     */
    public boolean isForgotten(Long tenantId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = ? "
                        + "  AND (edited_fields ->> ?) = 'true'",
                Integer.class, tenantId, userId, STATUS_ARCHIVED, TOMBSTONE_FORGOTTEN_KEY);
        return count != null && count > 0;
    }

    // ---- Internals -----------------------------------------------------

    private EditResult updateEditedFields(Long tenantId, String userId,
                                          java.util.function.Consumer<Map<String, Object>> mutator) {
        if (tenantId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId + userId required");
        }
        Map<String, Object> row = loadLiveRow(tenantId, userId);
        assertTenant(row, tenantId);
        String pid = (String) row.get("pid");
        int version = ((Number) row.get("version")).intValue();
        String status = (String) row.get("status");

        Map<String, Object> existing = readEditedFields(pid);
        mutator.accept(existing);

        jdbcTemplate.update(
                "UPDATE ab_agent_user_soul_profile SET edited_fields = ?::jsonb WHERE pid = ?",
                toJson(existing), pid);
        return new EditResult(pid, version, existing, status);
    }

    /**
     * Load the most recent non-ARCHIVED row for this user — ACTIVE beats
     * DRAFT beats SUPERSEDED. Throws {@link IllegalArgumentException} if
     * there is no row, or {@link IllegalStateException} if every row is
     * ARCHIVED (GDPR-forgotten).
     */
    private Map<String, Object> loadLiveRow(Long tenantId, String userId) {
        if (tenantId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId + userId required");
        }
        List<Map<String, Object>> live = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, version, status FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? "
                        + "  AND status IN (?, ?, ?) "
                        + "ORDER BY CASE status "
                        + "  WHEN ? THEN 0 "
                        + "  WHEN ? THEN 1 "
                        + "  WHEN ? THEN 2 "
                        + "  END, version DESC "
                        + "LIMIT 1",
                tenantId, userId,
                STATUS_ACTIVE, STATUS_DRAFT, STATUS_SUPERSEDED,
                STATUS_ACTIVE, STATUS_DRAFT, STATUS_SUPERSEDED);
        if (!live.isEmpty()) {
            return live.get(0);
        }
        // No live row. Distinguish between "nothing" and "forgotten".
        Integer archivedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = ?",
                Integer.class, tenantId, userId, STATUS_ARCHIVED);
        if (archivedCount != null && archivedCount > 0) {
            throw new IllegalStateException(
                    "profile ARCHIVED (GDPR-forgotten) for tenant=" + tenantId + " user=" + userId);
        }
        throw new IllegalArgumentException(
                "no soul profile found for tenant=" + tenantId + " user=" + userId);
    }

    private Map<String, Object> readEditedFields(String pid) {
        String text = jdbcTemplate.queryForObject(
                "SELECT COALESCE(edited_fields::text, '{}') FROM ab_agent_user_soul_profile WHERE pid = ?",
                String.class, pid);
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = MAPPER.readValue(text, new TypeReference<>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException e) {
            // Explicit validation: edited_fields should always be a JSON object.
            // If it isn't, the row is corrupt and we must not silently clobber.
            throw new IllegalStateException("corrupt edited_fields for pid " + pid, e);
        }
    }

    private static void assertTenant(Map<String, Object> row, Long tenantId) {
        Number rowTenant = (Number) row.get("tenant_id");
        if (rowTenant == null || !rowTenant.toString().equals(tenantId.toString())) {
            throw new IllegalArgumentException(
                    "tenant mismatch: row tenant=" + rowTenant + " caller=" + tenantId);
        }
    }

    private static void requireFieldPath(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath required");
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json serialisation failed", e);
        }
    }
}
