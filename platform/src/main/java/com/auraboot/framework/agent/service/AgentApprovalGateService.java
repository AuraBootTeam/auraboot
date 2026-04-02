package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.event.AgentApprovalEvent;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.event.AuraEventBus;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentApprovalGateService {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final AuraEventBus eventBus;
    private final AgentDispatchHandler dispatchHandler;

    public AgentApprovalGateService(DynamicDataMapper dynamicDataMapper,
                                     ObjectMapper objectMapper,
                                     AuraEventBus eventBus,
                                     @Lazy AgentDispatchHandler dispatchHandler) {
        this.dynamicDataMapper = dynamicDataMapper;
        this.objectMapper = objectMapper;
        this.eventBus = eventBus;
        this.dispatchHandler = dispatchHandler;
    }

    /**
     * Check if the given tool execution requires approval based on active policies.
     * Returns the approval request PID if approval is needed, null otherwise.
     *
     * <p>Idempotency: if an approval with the same key ({runId}:{toolCode}) already exists
     * for this tenant, the existing PID is returned without creating a duplicate record.
     * This prevents double-execution when the caller retries or concurrent requests race.
     */
    public String checkAndRequestApproval(Long tenantId, String runId, String taskId,
                                           String toolCode, String toolDescription,
                                           Map<String, Object> requestData, boolean toolRequiresApproval) {
        if (!toolRequiresApproval && !matchesAnyPolicy(tenantId, toolCode, requestData)) {
            return null;
        }

        // Build idempotency key: {runId}:{toolCode}
        String idempotencyKey = (runId != null ? runId : "norun") + ":" + toolCode;

        // Check for an existing approval with this idempotency key to avoid duplicates
        String existingSql = "SELECT pid FROM ab_agent_approval " +
                "WHERE idempotency_key = #{params.key} AND tenant_id = #{params.tenantId}";
        List<Map<String, Object>> existing = dynamicDataMapper.selectByQuery(
                existingSql, Map.of("key", idempotencyKey, "tenantId", tenantId));
        if (!existing.isEmpty()) {
            String existingPid = (String) existing.get(0).get("pid");
            log.info("Idempotent approval returned: pid={}, key={}", existingPid, idempotencyKey);
            return existingPid;
        }

        // Resolve timeout configuration from the matching policy (or use defaults)
        PolicyTimeout policyTimeout = resolveTimeoutFromPolicy(tenantId, toolCode, requestData);

        try {
            String approvalPid = UniqueIdGenerator.generate();
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> approval = new HashMap<>();
            approval.put("pid", approvalPid);
            approval.put("tenant_id", tenantId);
            approval.put("run_id", runId);
            approval.put("task_id", taskId);
            approval.put("approval_type", "tool_call");
            approval.put("approval_title", "Agent requests approval: " + toolDescription);
            approval.put("approval_description", "Tool: " + toolCode);
            approval.put("request_data", objectMapper.writeValueAsString(requestData));
            approval.put("approval_status", "pending");
            approval.put("approval_subject_type", "action");
            approval.put("revalidate_policy", "none");
            approval.put("expires_at", now.plusHours(policyTimeout.timeoutHours));
            approval.put("auto_action", policyTimeout.autoAction);
            approval.put("idempotency_key", idempotencyKey);
            approval.put("created_at", now);
            approval.put("updated_at", now);

            dynamicDataMapper.insert("ab_agent_approval", approval);
            log.info("Approval requested: pid={}, tool={}, key={}, expires_at=+{}h, auto_action={}",
                    approvalPid, toolCode, idempotencyKey, policyTimeout.timeoutHours, policyTimeout.autoAction);
            return approvalPid;
        } catch (Exception e) {
            log.error("Failed to create approval request: {}", e.getMessage(), e);
            return null;
        }
    }

    /** Immutable holder for resolved policy timeout configuration. */
    private record PolicyTimeout(int timeoutHours, String autoAction) {}

    /**
     * Resolve timeout_hours and timeout_action from the first matching active policy.
     * Falls back to 24 hours / REJECT when no policy matches or fields are null.
     */
    private PolicyTimeout resolveTimeoutFromPolicy(Long tenantId, String toolCode,
                                                    Map<String, Object> requestData) {
        String sql = "SELECT trigger_rules, timeout_hours, timeout_action " +
                "FROM ab_approval_policy WHERE tenant_id = #{params.tenantId} " +
                "AND policy_status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> policies = dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));

        for (Map<String, Object> policy : policies) {
            if (policy == null) continue;
            String triggerRulesJson = (String) policy.get("trigger_rules");
            if (triggerRulesJson == null) continue;
            try {
                List<Map<String, Object>> rules = objectMapper.readValue(triggerRulesJson, List.class);
                boolean matched = false;
                for (Map<String, Object> rule : rules) {
                    String type = (String) rule.get("type");
                    if ("tool_call".equals(type)) {
                        String pattern = (String) rule.get("pattern");
                        if (pattern != null && toolCode.matches(pattern.replace("*", ".*"))) {
                            matched = true;
                            break;
                        }
                    } else if ("cost_threshold".equals(type)) {
                        double threshold = ((Number) rule.getOrDefault("threshold", 0)).doubleValue();
                        double estimatedCost = requestData.containsKey("estimated_cost")
                                ? ((Number) requestData.get("estimated_cost")).doubleValue() : 0;
                        if (estimatedCost > threshold) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (matched) {
                    int timeoutHours = policy.get("timeout_hours") != null
                            ? ((Number) policy.get("timeout_hours")).intValue() : 24;
                    String autoAction = policy.get("timeout_action") != null
                            ? (String) policy.get("timeout_action") : "reject";
                    return new PolicyTimeout(timeoutHours, autoAction);
                }
            } catch (Exception e) {
                log.warn("Failed to parse trigger rules while resolving timeout: {}", e.getMessage());
            }
        }

        // No matching policy — apply conservative defaults
        return new PolicyTimeout(24, "reject");
    }

    public boolean isApproved(String approvalPid) {
        String sql = "SELECT approval_status FROM ab_agent_approval WHERE pid = #{params.pid}";
        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, Map.of("pid", approvalPid));
        if (results.isEmpty()) return false;
        return "approved".equals(results.get(0).get("approval_status"));
    }

    /**
     * Check whether the given user is an authorized approver for the specified approval request.
     *
     * <p>Authorization logic:
     * <ol>
     *   <li>If the approval has no associated policy (policy_id is null), any authenticated user
     *       within the same tenant is authorized — this preserves backward-compatible behavior.</li>
     *   <li>If a policy is linked, its {@code approver_rules} JSON array is evaluated.
     *       Supported rule types:
     *       <ul>
     *         <li>{@code USER}  — matches when {@code userId} equals the current user ID</li>
     *         <li>{@code ROLE}  — matches when {@code roleCode} is assigned to the current user</li>
     *       </ul>
     *       An empty {@code approver_rules} array or a null value also permits any authenticated user.
     *   </li>
     * </ol>
     *
     * @param tenantId   the tenant the approval belongs to
     * @param approvalPid the PID of the approval record
     * @param userId     the ID of the user attempting to approve / reject
     * @return {@code true} if the user is permitted to act on this approval
     */
    public boolean isAuthorizedApprover(Long tenantId, String approvalPid, Long userId) {
        // Load the approval record (must belong to the same tenant — no status filter here,
        // we allow checking even non-PENDING records for guard purposes)
        String approvalSql = "SELECT pid, policy_id FROM ab_agent_approval " +
                "WHERE pid = #{params.pid} AND tenant_id = #{params.tenantId}";
        List<Map<String, Object>> approvalRows = dynamicDataMapper.selectByQuery(
                approvalSql, Map.of("pid", approvalPid, "tenantId", tenantId));
        if (approvalRows.isEmpty() || approvalRows.get(0) == null) {
            // Approval not found in this tenant — deny
            return false;
        }

        String policyId = (String) approvalRows.get(0).get("policy_id");
        if (policyId == null || policyId.isBlank()) {
            // No policy linked — any authenticated tenant user may act
            return true;
        }

        // Load the policy's approver_rules
        String policySql = "SELECT pid, approver_rules FROM ab_approval_policy " +
                "WHERE pid = #{params.policyId} AND tenant_id = #{params.tenantId} AND deleted_flag = FALSE";
        List<Map<String, Object>> policyRows = dynamicDataMapper.selectByQuery(
                policySql, Map.of("policyId", policyId, "tenantId", tenantId));
        if (policyRows.isEmpty() || policyRows.get(0) == null) {
            // Policy not found — fall through and allow (policy was deleted after approval creation)
            log.warn("Approval {} references missing policy {}; allowing any authenticated user", approvalPid, policyId);
            return true;
        }

        String approverRulesJson = (String) policyRows.get(0).get("approver_rules");
        if (approverRulesJson == null || approverRulesJson.isBlank()) {
            // Policy exists but has no approver rules — allow any
            return true;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = objectMapper.readValue(approverRulesJson, List.class);
            if (rules == null || rules.isEmpty()) {
                return true;
            }
            return evaluateApproverRules(tenantId, userId, rules);
        } catch (Exception e) {
            log.error("Failed to parse approver_rules for policy {}: {}", policyId, e.getMessage());
            // Fail-secure: if we cannot parse the rules, deny access
            return false;
        }
    }

    /**
     * Evaluate the parsed approver rules against the given user.
     *
     * @param tenantId the current tenant (used for role lookup)
     * @param userId   the user to check
     * @param rules    parsed approver_rules JSON array
     * @return {@code true} if any rule matches
     */
    private boolean evaluateApproverRules(Long tenantId, Long userId, List<Map<String, Object>> rules) {
        for (Map<String, Object> rule : rules) {
            String type = (String) rule.get("type");
            if ("user".equals(type)) {
                Object ruleUserId = rule.get("userId");
                if (ruleUserId != null && userId.equals(toLong(ruleUserId))) {
                    return true;
                }
            } else if ("role".equals(type)) {
                String roleCode = (String) rule.get("roleCode");
                if (roleCode != null && userHasRole(tenantId, userId, roleCode)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check whether a user has a given role (by role code) in the specified tenant.
     */
    private boolean userHasRole(Long tenantId, Long userId, String roleCode) {
        String sql = "SELECT r.code FROM ab_user_role ur " +
                "JOIN ab_role r ON r.id = ur.role_id " +
                "WHERE ur.user_id = #{params.userId} AND ur.tenant_id = #{params.tenantId} " +
                "AND ur.status = 'active' AND ur.deleted_flag = FALSE " +
                "AND r.deleted_flag = FALSE AND r.status = 'active'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                sql, Map.of("userId", userId, "tenantId", tenantId));
        Set<String> roleCodes = rows.stream()
                .map(r -> (String) r.get("code"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return roleCodes.contains(roleCode);
    }

    /**
     * Safely convert a numeric value (Integer or Long) to Long.
     */
    private Long toLong(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        return null;
    }

    /**
     * Approve a pending approval request.
     * Updates the approval status to APPROVED, publishes an event,
     * and automatically resumes the paused agent run.
     *
     * @return the approval record, or null if not found / not in PENDING state
     * @throws IllegalStateException if the approval has already been processed (APPROVED/REJECTED/EXPIRED)
     *         to distinguish double-execution from "not found"
     */
    public Map<String, Object> approve(Long tenantId, String approvalPid, Long approverId) {
        // Guard: check for already-processed approval to prevent double-execution
        Map<String, Object> existing = loadApprovalAnyStatus(tenantId, approvalPid);
        if (existing != null) {
            String status = (String) existing.get("approval_status");
            if ("approved".equals(status)) {
                log.warn("Double-execution attempt blocked: approval {} is already APPROVED", approvalPid);
                throw new IllegalStateException("Approval already processed: " + approvalPid);
            }
            if ("rejected".equals(status) || "expired".equals(status)) {
                log.warn("Approve attempt on terminal approval {}: status={}", approvalPid, status);
                return null;
            }
        }

        Map<String, Object> approval = loadPendingApproval(tenantId, approvalPid);
        if (approval == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> update = new HashMap<>();
        update.put("approval_status", "approved");
        update.put("approver_id", approverId);
        update.put("approved_at", now);
        update.put("updated_at", now);
        dynamicDataMapper.update("ab_agent_approval", update, Map.of("pid", approvalPid));
        log.info("Approval approved: pid={}, approver={}", approvalPid, approverId);

        String runPid = (String) approval.get("run_id");
        String agentCode = resolveAgentCode(tenantId, runPid);

        // Publish domain event
        eventBus.publishAfterCommit(new AgentApprovalEvent(
                tenantId, approvalPid, runPid, agentCode, "approved", approverId));

        // Auto-resume the paused agent run
        resumeRunAfterApproval(tenantId, runPid);

        approval.put("approval_status", "approved");
        return approval;
    }

    /**
     * Reject a pending approval request.
     * Updates the approval status to REJECTED, publishes an event,
     * and marks the associated agent run as FAILED.
     *
     * @return the approval record, or null if not found / not in PENDING state
     */
    public Map<String, Object> reject(Long tenantId, String approvalPid, Long approverId, String reason) {
        Map<String, Object> approval = loadPendingApproval(tenantId, approvalPid);
        if (approval == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> update = new HashMap<>();
        update.put("approval_status", "rejected");
        update.put("approver_id", approverId);
        update.put("approved_at", now);
        update.put("rejection_reason", reason != null ? reason : "Rejected by user");
        update.put("updated_at", now);
        dynamicDataMapper.update("ab_agent_approval", update, Map.of("pid", approvalPid));
        log.info("Approval rejected: pid={}, approver={}, reason={}", approvalPid, approverId, reason);

        String runPid = (String) approval.get("run_id");
        String agentCode = resolveAgentCode(tenantId, runPid);

        // Publish domain event
        eventBus.publishAfterCommit(new AgentApprovalEvent(
                tenantId, approvalPid, runPid, agentCode, "rejected", approverId));

        // Fail the associated agent run
        failRunOnRejection(runPid, "Approval rejected by user");

        approval.put("approval_status", "rejected");
        return approval;
    }

    /**
     * Scheduled job: auto-expire pending approvals whose expires_at has passed.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelay = 300000)
    public void enforceApprovalTimeouts() {
        String sql = "SELECT pid, tenant_id, run_id, task_id FROM ab_agent_approval " +
                "WHERE approval_status = 'pending' AND expires_at IS NOT NULL AND expires_at < NOW()";
        List<Map<String, Object>> expired = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());

        if (expired.isEmpty()) {
            return;
        }

        log.info("Enforcing approval timeouts: {} expired approvals found", expired.size());
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> approval : expired) {
            String pid = (String) approval.get("pid");
            try {
                Map<String, Object> update = new HashMap<>();
                update.put("approval_status", "expired");
                update.put("rejection_reason", "Auto-expired: approval timeout exceeded");
                update.put("updated_at", now);
                dynamicDataMapper.update("ab_agent_approval", update, Map.of("pid", pid));

                String runPid = (String) approval.get("run_id");
                Long tenantId = approval.get("tenant_id") != null
                        ? ((Number) approval.get("tenant_id")).longValue() : null;
                String agentCode = resolveAgentCode(tenantId, runPid);

                // Publish domain event
                if (tenantId != null) {
                    eventBus.publishAfterCommit(new AgentApprovalEvent(
                            tenantId, pid, runPid, agentCode, "expired", null));
                }

                // Fail the associated agent run
                failRunOnRejection(runPid, "Approval expired");

                log.info("Approval expired: pid={}, run_id={}, task_id={}", pid,
                        runPid, approval.get("task_id"));
            } catch (Exception e) {
                log.error("Failed to expire approval {}: {}", pid, e.getMessage());
            }
        }
    }

    // ---- internal helpers ----

    /**
     * Load a pending approval by pid and tenant. Returns null if not found or not PENDING.
     */
    private Map<String, Object> loadPendingApproval(Long tenantId, String approvalPid) {
        String sql = "SELECT * FROM ab_agent_approval WHERE pid = #{params.pid} " +
                "AND tenant_id = #{params.tenantId} AND approval_status = 'pending'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("pid", approvalPid, "tenantId", tenantId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Load an approval record regardless of its current status (used to detect already-processed records).
     * Returns null if no record is found for the given pid and tenant.
     */
    private Map<String, Object> loadApprovalAnyStatus(Long tenantId, String approvalPid) {
        String sql = "SELECT pid, approval_status FROM ab_agent_approval " +
                "WHERE pid = #{params.pid} AND tenant_id = #{params.tenantId}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("pid", approvalPid, "tenantId", tenantId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Resolve agent code from the associated run record.
     */
    private String resolveAgentCode(Long tenantId, String runPid) {
        if (runPid == null) return null;
        String sql = "SELECT agent_id FROM ab_agent_run WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of("pid", runPid));
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("agent_id");
    }

    /**
     * Resume the paused agent run after approval.
     * Loads the run to find task_id and agent_id, then dispatches with resume.
     */
    private void resumeRunAfterApproval(Long tenantId, String runPid) {
        if (runPid == null) {
            log.warn("Cannot resume: no run_id on approved approval");
            return;
        }

        String sql = "SELECT * FROM ab_agent_run WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of("pid", runPid));
        if (rows.isEmpty()) {
            log.warn("Cannot resume: run not found: {}", runPid);
            return;
        }

        Map<String, Object> run = rows.get(0);
        String status = (String) run.get("run_status");
        if (!"pending".equals(status)) {
            log.info("Run {} is not PENDING (status={}), skipping auto-resume", runPid, status);
            return;
        }

        String taskPid = (String) run.get("task_id");
        String agentCode = (String) run.get("agent_id");
        if (taskPid == null || agentCode == null) {
            log.warn("Cannot resume run {}: missing task_id or agent_id", runPid);
            return;
        }

        log.info("Auto-resuming agent run {} after approval (task={}, agent={})", runPid, taskPid, agentCode);
        dispatchHandler.dispatchWithResume(tenantId, taskPid, agentCode, runPid);
    }

    /**
     * Mark an agent run as FAILED due to rejection or expiry.
     */
    private void failRunOnRejection(String runPid, String errorMessage) {
        if (runPid == null) return;

        String sql = "SELECT run_status FROM ab_agent_run WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of("pid", runPid));
        if (rows.isEmpty()) return;

        String status = (String) rows.get(0).get("run_status");
        if (!"pending".equals(status)) {
            log.info("Run {} is not PENDING (status={}), skipping fail-on-rejection", runPid, status);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> update = new HashMap<>();
        update.put("run_status", "failed");
        update.put("error_message", errorMessage);
        update.put("completed_at", now);
        update.put("updated_at", now);
        dynamicDataMapper.update("ab_agent_run", update, Map.of("pid", runPid));
        log.info("Run {} marked as FAILED: {}", runPid, errorMessage);
    }

    private boolean matchesAnyPolicy(Long tenantId, String toolCode, Map<String, Object> requestData) {
        String sql = "SELECT pid, trigger_rules FROM ab_approval_policy WHERE tenant_id = #{params.tenantId} " +
                "AND policy_status = 'active' AND deleted_flag = FALSE";
        List<Map<String, Object>> policies = dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));

        for (Map<String, Object> policy : policies) {
            if (policy == null) continue;
            String triggerRulesJson = (String) policy.get("trigger_rules");
            if (triggerRulesJson == null) continue;
            try {
                List<Map<String, Object>> rules = objectMapper.readValue(triggerRulesJson, List.class);
                for (Map<String, Object> rule : rules) {
                    String type = (String) rule.get("type");
                    if ("tool_call".equals(type)) {
                        String pattern = (String) rule.get("pattern");
                        if (pattern != null && toolCode.matches(pattern.replace("*", ".*"))) {
                            return true;
                        }
                    } else if ("cost_threshold".equals(type)) {
                        double threshold = ((Number) rule.getOrDefault("threshold", 0)).doubleValue();
                        double estimatedCost = requestData.containsKey("estimated_cost")
                                ? ((Number) requestData.get("estimated_cost")).doubleValue() : 0;
                        if (estimatedCost > threshold) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse trigger rules: {}", e.getMessage());
            }
        }
        return false;
    }
}
