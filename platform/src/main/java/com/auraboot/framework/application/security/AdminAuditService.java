package com.auraboot.framework.application.security;

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
     * @param actorUserId         authenticated user (nullable if MetaContext was incomplete)
     * @param actorRole           resolved role that was checked ({@code tenant_admin} or
     *                            {@code platform_admin})
     * @param path                request URI
     * @param method              HTTP method (GET, POST, …)
     * @param status              HTTP response status code
     * @param requestBodySummary  redacted body summary produced by
     *                            {@link RequestBodySummarizer}, or {@code null}
     * @param latencyMs           wall-clock latency measured in the interceptor
     */
    @Async("adminAuditExecutor")
    public void logAdminAction(Long tenantId,
                               Long actorUserId,
                               String actorRole,
                               String path,
                               String method,
                               int status,
                               String requestBodySummary,
                               Integer latencyMs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO ab_admin_action_log " +
                            "(tenant_id, actor_user_id, actor_role, path, method, " +
                            " status, request_body_summary, latency_ms, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    tenantId,
                    actorUserId != null ? actorUserId.toString() : null,
                    actorRole,
                    path,
                    method,
                    status,
                    requestBodySummary,
                    latencyMs);
        } catch (Exception e) {
            log.warn("admin audit insert failed: tenantId={} userId={} path={} err={}",
                    tenantId, actorUserId, path, e.getMessage());
        }
    }
}
