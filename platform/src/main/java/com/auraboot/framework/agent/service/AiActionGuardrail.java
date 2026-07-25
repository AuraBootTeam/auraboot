package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import com.auraboot.framework.observability.TraceCorrelation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side enforcement of {@link AiActionRiskLevel#BLOCKED}: the action classes an AI
 * must never take, no matter who would approve them.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link AiActionRiskAssessor} computed a risk level nobody consulted. Its only caller
 * was the mobile controller that exposes it, no client called those endpoints, and
 * {@code ab_ai_action_audit_log} held 0 rows in all six databases checked. So
 * "BLOCKED — AI must not suggest it" was a value returned to a caller that did not exist,
 * and a client that ignored the answer could execute the action anyway.
 *
 * <p>The tool loop already had layered authorization — tool ACL, runtime authorization,
 * BIF-risk escalation, per-tool approval, confirmation — but every layer could be
 * <em>satisfied</em>: each one either allows, or asks a human, who can then allow. There
 * was no tier meaning "not even with approval". That is precisely what BLOCKED means, and
 * it is why this check runs before the approval machinery rather than inside it: creating
 * an approval request for a forbidden action invites someone to approve it.
 *
 * <h2>What gets audited</h2>
 *
 * <p>Every BLOCKED refusal, and every allowed {@link AiActionRiskLevel#HIGH} action, is
 * written to {@code ab_ai_action_audit_log} with {@code actor_type = agent} and no user.
 * MEDIUM and LOW are not recorded: at tool-call volume that is noise, and the question
 * this log answers is "what did an autonomous run try to do that mattered".
 */
@Slf4j
@Service
public class AiActionGuardrail {

    private static final String ACTION_TYPE = "execute_command";
    private static final String METRIC_BLOCKED = "auraboot_ai_action_blocked_total";

    /** Command-backed tools are named {@code cmd_<code>} or {@code cmd:<code>}. */
    private static final String[] COMMAND_TOOL_PREFIXES = {"cmd_", "cmd:"};

    /**
     * Mirrors AiActionRiskAssessor's BLOCKED_EXECUTION_TYPES. Kept here as an independent
     * check against the tool definition so a failed command lookup cannot downgrade a
     * delete to MEDIUM.
     */
    private static final java.util.Set<String> BLOCKED_OPERATION_KINDS = java.util.Set.of("delete");

    private final AiActionRiskAssessor riskAssessor;
    private final AiActionAuditService auditService;
    private final ObjectProvider<Tracer> tracerProvider;
    private final MeterRegistry meterRegistry;

    public AiActionGuardrail(AiActionRiskAssessor riskAssessor,
                             AiActionAuditService auditService,
                             ObjectProvider<Tracer> tracerProvider,
                             MeterRegistry meterRegistry) {
        this.riskAssessor = riskAssessor;
        this.auditService = auditService;
        this.tracerProvider = tracerProvider;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The guardrail's verdict for one tool call.
     *
     * @param allowed     false only for {@link AiActionRiskLevel#BLOCKED}
     * @param level       the assessed level, or {@code null} for a non-command tool
     * @param commandCode the command the tool maps to, or {@code null}
     * @param message     an LLM-facing refusal message when {@code !allowed}
     */
    public record Decision(boolean allowed, AiActionRiskLevel level,
                           String commandCode, String message) {

        static Decision allow(AiActionRiskLevel level, String commandCode) {
            return new Decision(true, level, commandCode, null);
        }
    }

    /**
     * Assess a tool call and refuse it when the underlying command is forbidden for AI.
     *
     * <p>Non-command tools (queries, search, handoff…) are out of scope and allowed
     * without a database read — the assessor's escalation rules are defined in terms of a
     * command's {@code execution_config.type} and declared {@code cmd_risk_level}.
     *
     * <p>The command code comes from {@link AgentToolDefinition#getSourceCode()} rather
     * than from parsing the tool name: the name is a provider-chosen display string in
     * some paths, and a guardrail that silently resolves nothing looks identical to a
     * guardrail that approves everything.
     *
     * <p>{@code operationKind} is consulted as an independent second signal. The assessor
     * falls back to {@code "update"} when it cannot read a command's
     * {@code execution_config} — its own comments record that this exact fallback once
     * downgraded a BLOCKED delete to MEDIUM — so a tool definition that already says
     * {@code delete} is treated as BLOCKED regardless of what the lookup returns. Two
     * independent paths to the same fact, and the stricter one wins.
     */
    public Decision check(Long tenantId, String agentCode, String runPid,
                          String toolName, AgentToolDefinition toolDef,
                          Map<String, Object> input) {
        String commandCode = commandCodeOf(toolName, toolDef);
        if (commandCode == null || tenantId == null) {
            return Decision.allow(null, null);
        }

        AiActionRiskLevel level = riskAssessor.assess(ACTION_TYPE, commandCode, tenantId);
        if (isBlockedOperationKind(toolDef) && level != AiActionRiskLevel.BLOCKED) {
            log.warn("Tool definition for command {} declares operationKind={} but the assessor "
                            + "returned {}; escalating to BLOCKED (strictest wins)",
                    commandCode, toolDef.getOperationKind(), level);
            level = AiActionRiskLevel.BLOCKED;
        }

        if (level == AiActionRiskLevel.BLOCKED) {
            String message = "Error: Command '" + commandCode + "' is blocked for AI execution "
                    + "(risk level BLOCKED). No data was changed, and this cannot be approved. "
                    + "Ask a human to perform it directly.";
            audit(tenantId, agentCode, runPid, commandCode, level,
                    AiActionAuditService.DECISION_BLOCKED, "blocked", message, toolName, input);
            count(agentCode, commandCode);
            log.warn("AI action guardrail refused a BLOCKED command: tenant={} agent={} run={} "
                            + "tool={} command={}",
                    tenantId, agentCode, runPid, toolName, commandCode);
            return new Decision(false, level, commandCode, message);
        }

        if (level == AiActionRiskLevel.HIGH) {
            audit(tenantId, agentCode, runPid, commandCode, level,
                    AiActionAuditService.DECISION_AUTO, null, null, toolName, input);
        }

        return Decision.allow(level, commandCode);
    }

    /**
     * The command a tool maps to, or {@code null} when it is not command-backed.
     * {@code sourceCode} is authoritative; the name prefix is a fallback for providers
     * that do not populate it.
     */
    static String commandCodeOf(String toolName, AgentToolDefinition toolDef) {
        if (toolDef != null && isCommandTool(toolDef)) {
            String source = toolDef.getSourceCode();
            if (source != null && !source.isBlank()) {
                return stripPrefix(source);
            }
        }
        return stripPrefixOrNull(toolName);
    }

    /**
     * A command-backed tool. Platform built-ins carry a {@code platform.*} source code and
     * are not DSL commands, so they are excluded — the assessor has nothing to look up
     * for them.
     */
    private static boolean isCommandTool(AgentToolDefinition toolDef) {
        String type = toolDef.getToolType();
        String source = toolDef.getSourceCode();
        if (source != null && source.startsWith("platform.")) {
            return false;
        }
        if (type != null && type.toLowerCase(Locale.ROOT).contains("command")) {
            return true;
        }
        return stripPrefixOrNull(toolDef.getName()) != null;
    }

    /** Operation kinds an AI must never execute, independent of any database lookup. */
    private static boolean isBlockedOperationKind(AgentToolDefinition toolDef) {
        if (toolDef == null || toolDef.getOperationKind() == null) {
            return false;
        }
        return BLOCKED_OPERATION_KINDS.contains(toolDef.getOperationKind().toLowerCase(Locale.ROOT));
    }

    private static String stripPrefix(String value) {
        String stripped = stripPrefixOrNull(value);
        return stripped != null ? stripped : value;
    }

    private static String stripPrefixOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (String prefix : COMMAND_TOOL_PREFIXES) {
            if (value.startsWith(prefix) && value.length() > prefix.length()) {
                return value.substring(prefix.length());
            }
        }
        return null;
    }

    private void audit(Long tenantId, String agentCode, String runPid, String commandCode,
                       AiActionRiskLevel level, String decision, String executionResult,
                       String errorMessage, String toolName, Map<String, Object> input) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", toolName);
            if (input != null && !input.isEmpty()) {
                payload.put("input", input);
            }
            auditService.recordAgentAction(tenantId, agentCode, runPid,
                    TraceCorrelation.traceId(tracerProvider.getIfAvailable()),
                    ACTION_TYPE, commandCode, level.code(), decision,
                    executionResult, errorMessage, payload);
        } catch (Exception e) {
            // A failed audit write must not turn a correct refusal into an allow. The
            // refusal has already been decided by the caller; this is the record of it.
            log.warn("Failed to record AI action audit for command {} (decision={}): {}",
                    commandCode, decision, e.getMessage());
        }
    }

    private void count(String agentCode, String commandCode) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_BLOCKED)
                .description("AI tool calls refused because the command is BLOCKED for AI execution")
                .tag("agent", agentCode == null ? "unknown" : agentCode)
                .tag("command", commandCode == null ? "unknown" : commandCode)
                .register(meterRegistry)
                .increment();
    }
}
