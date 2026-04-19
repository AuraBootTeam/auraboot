package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
import com.auraboot.framework.agent.service.UserSoulProfileDeriver;
import com.auraboot.framework.agent.service.UserSoulProfileEditor;
import com.auraboot.framework.agent.service.UserSoulProfileEditor.EditResult;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * User Soul Profile REST API (plan §5.6, PR-78).
 *
 * <p>Two controllers live in this file under a shared base path prefix scheme:
 * {@link UserSoulProfileController} covers the per-user endpoints at
 * {@code /api/user/soul-profile/**}. Admin endpoints live in
 * {@link UserSoulProfileAdminController} at {@code /api/admin/user-soul-profiles/**}
 * and expose metadata only — never {@code profile} content or
 * {@code edited_fields}.
 *
 * <p>All user endpoints are tenant-scoped via
 * {@link MetaContext#getCurrentTenantId()} and user-scoped via
 * {@link MetaContext#getCurrentUserId()}. A user sees ONLY their own profile.
 *
 * <p>{@code /derive-now} is rate-limited to one successful trigger per 24h
 * per {@code (tenantId, userId)} by an in-process Caffeine cache. Multi-instance
 * deployments may therefore allow up to N invocations per day (N = instance
 * count); acceptable for v1.
 */
@Slf4j
@RestController
@RequestMapping(UserSoulProfileController.BASE_PATH)
public class UserSoulProfileController {

    public static final String BASE_PATH = "/api/user/soul-profile";

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_SUPERSEDED = "SUPERSEDED";
    static final String STATUS_ARCHIVED = "ARCHIVED";

    static final String FIELD_PID = "pid";
    static final String FIELD_VERSION = "version";
    static final String FIELD_STATUS = "status";
    static final String FIELD_FIELD = "field";
    static final String FIELD_TEXT = "text";

    static final Duration DERIVE_NOW_MIN_INTERVAL = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final UserSoulProfileEditor editor;
    private final UserSoulProfileDeriver deriver;
    private final UserSoulProfileMetrics metrics;

    /**
     * In-process rate limiter for /derive-now. Key: (tenantId, userId); value:
     * Instant of the last successful trigger. 24h TTL — entries evict on their
     * own after the cooldown expires.
     */
    private final Cache<String, Instant> deriveNowRateLimiter = Caffeine.newBuilder()
            .expireAfterWrite(DERIVE_NOW_MIN_INTERVAL)
            .maximumSize(100_000)
            .build();

    private final ObjectMapper objectMapper;

    @Autowired
    public UserSoulProfileController(JdbcTemplate jdbcTemplate,
                                     UserSoulProfileEditor editor,
                                     UserSoulProfileDeriver deriver,
                                     UserSoulProfileMetrics metrics,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.editor = editor;
        this.deriver = deriver;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // GET / — active profile for current user
    // =========================================================================

    @GetMapping
    public ApiResponse<Map<String, Object>> active() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = requireUserId();
        if (userId == null) return ApiResponse.error(401, "no user context");

        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT pid, tenant_id, user_id, version, status, " +
                            "       profile::text AS profile_json, " +
                            "       edited_fields::text AS edited_fields_json, " +
                            "       language_preference, derivation_confidence, " +
                            "       stale_flagged_at, activated_at, created_at, " +
                            "       hidden_at " +
                            "FROM ab_agent_user_soul_profile " +
                            "WHERE tenant_id = ? AND user_id = ? AND status = ? " +
                            "LIMIT 1",
                    tenantId, userId, STATUS_ACTIVE);
            parseJsonField(row, "profile_json", "profile");
            parseJsonField(row, "edited_fields_json", "edited_fields");
            return ApiResponse.ok(row);
        } catch (EmptyResultDataAccessException none) {
            return ApiResponse.error(404, "no active soul profile");
        }
    }

    /**
     * Parse a stringified JSONB column into a nested object under a new key,
     * so frontend consumers receive {@code profile} as a real object rather
     * than a raw string. Null / blank JSON becomes null. Removes the
     * original string-valued key to avoid duplication on the wire.
     */
    private void parseJsonField(Map<String, Object> row, String stringKey, String objectKey) {
        Object raw = row.remove(stringKey);
        if (!(raw instanceof String s) || s.isBlank()) {
            row.put(objectKey, null);
            return;
        }
        try {
            row.put(objectKey, objectMapper.readValue(s, Object.class));
        } catch (Exception e) {
            log.warn("UserSoulProfile: failed to parse {} as JSON: {}", stringKey, e.getMessage());
            row.put(objectKey, null);
        }
    }

    // =========================================================================
    // GET /history — superseded / archived versions (metadata only)
    // =========================================================================

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = requireUserId();
        if (userId == null) return ApiResponse.error(401, "no user context");

        // History excludes ACTIVE (that's GET /), and NEVER leaks ARCHIVED
        // profile content — GDPR-forgotten rows surface only as metadata
        // so the user still sees a timeline of versions.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, version, status, derivation_confidence, " +
                        "       created_at, superseded_at, activated_at " +
                        "FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND user_id = ? AND status <> ? " +
                        "ORDER BY version DESC " +
                        "LIMIT 20",
                tenantId, userId, STATUS_ACTIVE);
        return ApiResponse.ok(rows);
    }

    // =========================================================================
    // GET /{pid} — specific version (own only)
    // =========================================================================

    @GetMapping("/{pid}")
    public ApiResponse<Map<String, Object>> byPid(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = requireUserId();
        if (userId == null) return ApiResponse.error(401, "no user context");

        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT pid, tenant_id, user_id, version, status, " +
                            "       profile::text AS profile_json, " +
                            "       edited_fields::text AS edited_fields_json, " +
                            "       language_preference, derivation_confidence, " +
                            "       stale_flagged_at, activated_at, created_at, " +
                            "       superseded_at, hidden_at " +
                            "FROM ab_agent_user_soul_profile " +
                            "WHERE pid = ? AND tenant_id = ? AND user_id = ? " +
                            "  AND status <> ?",
                    pid, tenantId, userId, STATUS_ARCHIVED);
            parseJsonField(row, "profile_json", "profile");
            parseJsonField(row, "edited_fields_json", "edited_fields");
            return ApiResponse.ok(row);
        } catch (EmptyResultDataAccessException none) {
            // Deliberately return the same 404 for: non-existent pid,
            // cross-user access, cross-tenant access. No information leak.
            return ApiResponse.error(404, "profile version not found");
        }
    }

    // =========================================================================
    // GET /export — GDPR data portability (user-only; full JSON dump)
    // =========================================================================

    /**
     * Dump every row the current user owns across all statuses (ACTIVE +
     * SUPERSEDED + ARCHIVED) as a JSON attachment. GDPR Article 20 (right to
     * data portability) — self-service only; admins cannot call this path
     * because admin endpoints live under a different controller and return
     * metadata only.
     *
     * <p>Includes full {@code profile} + {@code edited_fields} content
     * because the user is the data subject. Tombstone rows
     * ({@code edited_fields._forgotten = true}) are included so the export
     * is an honest record of what the server holds.
     */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = requireUserId();
        if (userId == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no user context");
            return;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, user_id, version, status, " +
                        "       profile::text AS profile_json, " +
                        "       edited_fields::text AS edited_fields_json, " +
                        "       profile_hash, language_preference, derivation_confidence, " +
                        "       stale_flagged_at, activated_at, superseded_at, " +
                        "       hidden_at, created_at " +
                        "FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND user_id = ? " +
                        "ORDER BY version DESC",
                tenantId, userId);

        List<Map<String, Object>> parsed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            parseJsonField(copy, "profile_json", "profile");
            parseJsonField(copy, "edited_fields_json", "edited_fields");
            parsed.add(copy);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "1.0");
        payload.put("exported_at", Instant.now().toString());
        payload.put("tenant_id", tenantId);
        payload.put("user_id", userId);
        payload.put("row_count", parsed.size());
        payload.put("profiles", parsed);

        byte[] body = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(payload);

        String filename = "user-soul-profile-" + userId + "-" +
                Instant.now().toString().replace(':', '-') + ".json";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }

    // =========================================================================
    // POST /pin, /hide, /edit, /reset — delegate to Editor
    // =========================================================================

    @PostMapping("/pin")
    public ApiResponse<Map<String, Object>> pin(@RequestBody Map<String, Object> body) {
        String field = requireStringField(body, FIELD_FIELD);
        if (field == null) return ApiResponse.error(400, "field required");
        return invokeEditor(() -> editor.pin(
                MetaContext.getCurrentTenantId(), requireUserId(), field));
    }

    @PostMapping("/hide")
    public ApiResponse<Map<String, Object>> hide(@RequestBody Map<String, Object> body) {
        String field = requireStringField(body, FIELD_FIELD);
        if (field == null) return ApiResponse.error(400, "field required");
        return invokeEditor(() -> editor.hide(
                MetaContext.getCurrentTenantId(), requireUserId(), field));
    }

    @PostMapping("/edit")
    public ApiResponse<Map<String, Object>> edit(@RequestBody Map<String, Object> body) {
        String field = requireStringField(body, FIELD_FIELD);
        String text = body == null ? null : Objects.toString(body.get(FIELD_TEXT), null);
        if (field == null) return ApiResponse.error(400, "field required");
        if (text == null) return ApiResponse.error(400, "text required");
        return invokeEditor(() -> editor.edit(
                MetaContext.getCurrentTenantId(), requireUserId(), field, text));
    }

    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset(@RequestBody(required = false) Map<String, Object> body) {
        String field = body == null ? null : Objects.toString(body.get(FIELD_FIELD), null);
        return invokeEditor(() -> editor.reset(
                MetaContext.getCurrentTenantId(), requireUserId(),
                (field == null || field.isBlank()) ? null : field));
    }

    @PostMapping("/hide-profile")
    public ApiResponse<Map<String, Object>> hideProfile() {
        return invokeEditor(() -> editor.hideProfile(
                MetaContext.getCurrentTenantId(), requireUserId()));
    }

    // =========================================================================
    // POST /forget — GDPR cascade, idempotent
    // =========================================================================

    @PostMapping("/forget")
    public ApiResponse<Map<String, Object>> forget() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = requireUserId();
        if (userId == null) return ApiResponse.error(401, "no user context");

        // Idempotency: when the user has NO rows at all, Editor.forgetProfile
        // throws IllegalArgumentException. Treat as "nothing to do" 200.
        // When the user is already tombstoned, re-calling still succeeds and
        // inserts a fresh tombstone version (see Editor).
        try {
            EditResult r = editor.forgetProfile(tenantId, userId);
            Map<String, Object> out = toResultMap(r);
            out.put("noop", false);
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException nothingToDo) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("noop", true);
            out.put("message", "no profile to forget");
            return ApiResponse.ok(out);
        }
    }

    // =========================================================================
    // POST /derive-now — manual trigger, rate-limited 1/day
    // =========================================================================

    @PostMapping("/derive-now")
    public ApiResponse<Map<String, Object>> deriveNow() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = requireUserId();
        if (userId == null) return ApiResponse.error(401, "no user context");

        String cacheKey = rateLimiterKey(tenantId, userId);
        Instant last = deriveNowRateLimiter.getIfPresent(cacheKey);
        if (last != null) {
            long retryAfterSeconds = Math.max(0L,
                    DERIVE_NOW_MIN_INTERVAL.getSeconds() -
                            Duration.between(last, Instant.now()).getSeconds());
            metrics.recordManualDerive(tenantId, UserSoulProfileMetrics.MANUAL_OUTCOME_RATE_LIMITED);
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("retry_after_seconds", retryAfterSeconds);
            ctx.put("last_triggered_at", last.toString());
            return ApiResponse.error(429, "derive-now is rate-limited to 1/day", ctx);
        }

        UserSoulProfileDeriver.DerivationResult r = deriver.deriveForUser(tenantId, userId);
        deriveNowRateLimiter.put(cacheKey, Instant.now());
        metrics.recordManualDerive(tenantId, UserSoulProfileMetrics.MANUAL_OUTCOME_TRIGGERED);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", r.outcome().name());
        out.put("profile_pid", r.profilePid());
        out.put("profile_hash", r.profileHash());
        return ApiResponse.ok(out);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String requireUserId() {
        Long id = MetaContext.getCurrentUserId();
        return id == null ? null : id.toString();
    }

    private static String requireStringField(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static String rateLimiterKey(Long tenantId, String userId) {
        return tenantId + ":" + userId;
    }

    private ApiResponse<Map<String, Object>> invokeEditor(EditorInvocation call) {
        String userId = requireUserId();
        if (userId == null) return ApiResponse.error(401, "no user context");
        try {
            EditResult r = call.execute();
            return ApiResponse.ok(toResultMap(r));
        } catch (IllegalArgumentException notFound) {
            // Editor throws IllegalArgumentException when there is no live row.
            return ApiResponse.error(404, notFound.getMessage());
        } catch (IllegalStateException archived) {
            // Editor throws IllegalStateException when every row is ARCHIVED
            // (GDPR-forgotten). Surface as 409 — the resource exists but its
            // current state forbids further mutation.
            return ApiResponse.error(409, archived.getMessage());
        }
    }

    private static Map<String, Object> toResultMap(EditResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FIELD_PID, r.pid());
        out.put(FIELD_VERSION, r.version());
        out.put(FIELD_STATUS, r.status());
        out.put("edited_fields", r.editedFields());
        return out;
    }

    @FunctionalInterface
    private interface EditorInvocation {
        EditResult execute();
    }
}
