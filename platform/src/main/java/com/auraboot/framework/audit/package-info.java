/**
 * Cross-cutting administrative-action audit module.
 *
 * <p>Provides {@link com.auraboot.framework.audit.service.AdminEventLogService}
 * — a fire-and-forget service that records semantic domain events
 * (env lock/unlock, promotion apply, plugin install, admin user disable, …)
 * to {@code ab_admin_event_log}.
 *
 * <h2>When to use this module</h2>
 *
 * Use {@code AdminEventLogService.record} after any administrative action
 * that ops/compliance might later need to answer:
 * <em>"who did what semantic action to what resource, with what outcome?"</em>
 *
 * <p>Example:
 * <pre>{@code
 * adminEventLogService.record(AdminEventLog.builder()
 *     .actionType("environment.lock")     // domain.action format
 *     .resourceType("environment")
 *     .resourcePid(envPid)
 *     .success(true)
 *     .reason("freezing for prod cut")
 *     .build());
 * // tenantId / actorUserId / actorType / createdAt / pid auto-filled from MetaContext
 * }</pre>
 *
 * <h2>Fire-and-forget contract</h2>
 *
 * The service <strong>swallows all exceptions</strong> and never propagates
 * to the caller — audit-write failure must not break the action being audited.
 * Tradeoff: no transactional consistency between action and audit row. If the
 * action commits but audit write fails, the action is real and the audit log
 * is silently incomplete (logged at WARN). This is the right tradeoff for
 * administrative actions where blocking the user on audit-row write would
 * be worse than a missing audit line.
 *
 * <h2>Distinct from other audit tables</h2>
 *
 * The OSS platform has 4 other audit tables:
 *
 * <ul>
 *   <li>{@code ab_permission_audit_log} — DENY decisions on the permission
 *       evaluation hot path (avoid log spam). Service: {@code PermissionAuditService}.</li>
 *   <li>{@code ab_command_audit_log} — runtime command pipeline events.
 *       Service: {@code CommandAuditLogService}.</li>
 *   <li>{@code ab_query_audit_log} — runtime data-query observability
 *       (slow-query / row counts).</li>
 *   <li>{@code ab_admin_action_log} (PR #45 admin guard v2) —
 *       <strong>HTTP-request shape</strong>: path / method / status / latency_ms /
 *       request_body_summary, written by Spring interceptor on admin endpoints.
 *       Captures the request layer ("who called what URL when").</li>
 * </ul>
 *
 * <p>{@code ab_admin_event_log} (this module) is the
 * <strong>domain-event shape</strong>: action_type / resource_type / resource_pid /
 * success / payload(JSONB), written by service-layer code at the moment of
 * the action. Captures the semantic layer ("who did what semantic thing").
 *
 * <p>A single HTTP request can produce 0, 1, or N domain events. The two tables
 * are complementary; neither subsumes the other.
 *
 * <h2>Adding new event types</h2>
 *
 * <ol>
 *   <li>Pick an {@code action_type} in {@code domain.action} format
 *       (e.g. {@code plugin.install}, {@code admin_user.disable}).</li>
 *   <li>In the domain service implementation, after the action commits,
 *       call {@code adminEventLogService.record(...)} with the builder.</li>
 *   <li>For both success and failure paths — failure events have
 *       {@code success=false} + {@code reason=exception.getMessage()}.</li>
 *   <li>Add at least 1 IT assertion to the domain service's existing IT
 *       (1 line: {@code adminEventLogService.byResource(...)} + assert).</li>
 * </ol>
 *
 * @see com.auraboot.framework.audit.service.AdminEventLogService
 * @see com.auraboot.framework.audit.entity.AdminEventLog
 */
package com.auraboot.framework.audit;
