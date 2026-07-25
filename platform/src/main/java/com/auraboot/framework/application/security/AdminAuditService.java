package com.auraboot.framework.application.security;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Writes one row per admin request to {@code ab_admin_action_log} asynchronously.
 *
 * <p>The method is annotated with {@code @Async("adminAuditExecutor")} so the
 * insert runs on the dedicated {@link AdminAuditConfig#adminAuditExecutor()} thread
 * pool, never on the request thread. Failures are caught and logged at {@code WARN}
 * level; they are never re-thrown so that a DB hiccup cannot affect the HTTP response.
 *
 * <p>{@code actor_user_id} is stored as {@code VARCHAR(64)} in the schema — the
 * {@code Long} parameter is converted to its decimal string representation before
 * the insert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Asynchronously inserts one audit row.
     *
     * @param tenantId            tenant context of the request
     * @param actorUserId         authenticated user (non-null; caller must validate before invoking)
     * @param actorRole           resolved role that was checked ({@code tenant_admin} or
     *                            {@code platform_admin})
     * @param path                request URI
     * @param method              HTTP method (GET, POST, …)
     * @param status              HTTP response status code
     * @param requestBodySummary  redacted body summary produced by
     *                            {@link RequestBodySummarizer}, or {@code null}
     * @param latencyMs           wall-clock latency measured in the interceptor
     * @param traceId             OTel W3C traceId, captured by the caller on the request
     *                            thread. It cannot be read here: adminAuditExecutor has no
     *                            TaskDecorator (unlike taskExecutor / eventTaskExecutor /
     *                            exportTaskExecutor / asyncTaskExecutor), so neither the OTel
     *                            context nor MetaContext survives the hop, and the
     *                            MetaContext-snapshot fallback used by the permission audit
     *                            would silently write NULL.
     * @param spanId              OTel spanId, likewise captured by the caller
     */
    @Async("adminAuditExecutor")
    public void logAdminAction(Long tenantId,
                               Long actorUserId,
                               String actorRole,
                               String path,
                               String method,
                               int status,
                               String requestBodySummary,
                               Integer latencyMs,
                               String traceId,
                               String spanId) {
        Objects.requireNonNull(actorUserId, "actorUserId required for admin audit");
        try {
            jdbcTemplate.update(
                    "INSERT INTO ab_admin_action_log " +
                            "(tenant_id, actor_user_id, actor_role, path, method, " +
                            " status, request_body_summary, latency_ms, trace_id, span_id, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    tenantId,
                    actorUserId.toString(),
                    actorRole,
                    path,
                    method,
                    status,
                    requestBodySummary,
                    latencyMs,
                    traceId,
                    spanId);
        } catch (Exception e) {
            log.warn("admin audit insert failed: tenantId={} userId={} path={} err={}",
                    tenantId, actorUserId, path, e.getMessage());
        }
    }

    /**
     * Admin HTTP requests correlated to one OTel trace id, for the eagle-eye console.
     *
     * <p>Only the fields the console shows. {@code actor_user_id} is already a VARCHAR in this
     * table, so there is no snowflake-as-JSON-number hazard to guard against here — unlike the
     * other audit surfaces, whose entities carry Long ids.
     */
    public List<AdminActionView> findByTraceId(Long tenantId, String traceId, int limit) {
        if (tenantId == null || traceId == null || traceId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT path, method, status, actor_role, actor_user_id, latency_ms, created_at "
                        + "FROM ab_admin_action_log WHERE tenant_id = ? AND trace_id = ? "
                        + "ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> new AdminActionView(
                        rs.getString("path"),
                        rs.getString("method"),
                        rs.getInt("status"),
                        rs.getString("actor_role"),
                        rs.getString("actor_user_id"),
                        (Integer) rs.getObject("latency_ms"),
                        rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toInstant()),
                tenantId, traceId, limit);
    }

    /** One admin HTTP request, shaped for the eagle-eye console. */
    public record AdminActionView(String path, String method, int status, String actorRole,
                                  String actorUserId, Integer latencyMs, Instant createdAt) {
    }
}
