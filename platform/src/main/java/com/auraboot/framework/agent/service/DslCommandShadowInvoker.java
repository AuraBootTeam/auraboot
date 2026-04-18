package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// N12: do not catch CommandExecutor exceptions here. ShadowExecutor already
// wraps this invocation in try/catch and records shadowStatus="failed" on
// failure — swallowing here inflated output_match_rate because the caller
// saw a "successful" Map return and counted it as a match.

/**
 * Shadow invoker for DSL write commands (tool_ref = {@code dsl.command}
 * or {@code cmd_*}). PR-40 enables this: {@link CommandExecutor} now
 * supports a {@code dryRun} flag that forces a rollback at the end of
 * the wrapping transaction, so the full validation / idempotency /
 * entitlement / SOD / handler pipeline executes against real DB state
 * but leaves no residue.
 *
 * External side effects (BPM triggers, webhooks) are suppressed inside
 * the pipeline when {@code dryRun=true}.
 *
 * tool_ref resolution:
 *   - {@code cmd_<code>}       → command code = {@code <code>}
 *   - {@code dsl.command}      → command code from args.command_code
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DslCommandShadowInvoker implements ShadowToolInvoker {

    private final CommandExecutor commandExecutor;

    @Override
    public boolean supports(String toolRef) {
        if (toolRef == null) return false;
        return toolRef.startsWith("cmd_") || "dsl.command".equals(toolRef);
    }

    @Override
    public Map<String, Object> invokeShadow(Long tenantId, String toolRef, Map<String, Object> args) {
        String commandCode = resolveCommandCode(toolRef, args);
        if (commandCode == null || commandCode.isBlank()) {
            log.debug("DslCommandShadowInvoker: no command code for tool_ref={}", toolRef);
            return Map.of("status", "no_command_code");
        }

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setDryRun(true);
        if (args != null) {
            Object payload = args.get("payload");
            if (payload instanceof Map<?, ?> m) {
                Map<String, Object> coerced = new HashMap<>();
                m.forEach((k, v) -> coerced.put(String.valueOf(k), v));
                req.setPayload(coerced);
            }
            Object operationType = args.get("operation_type");
            if (operationType instanceof String s) req.setOperationType(s);
            Object targetRecordId = args.get("target_record_id");
            if (targetRecordId instanceof String s) req.setTargetRecordId(s);
        }

        // Exception from CommandExecutor propagates to the caller (ShadowExecutor),
        // which marks shadow_status="failed" in its own try/catch (see
        // ShadowExecutor.java ~line 103). Silent-swallow here would inflate
        // output_match_rate by returning a "successful" Map to the caller.
        CommandExecuteResult r = commandExecutor.execute(commandCode, req);

        Map<String, Object> out = new HashMap<>();
        out.put("command_code", commandCode);
        out.put("phase_reached", r == null ? null : r.getPhaseReached());
        out.put("data", r == null ? Map.of() : (r.getData() == null ? Map.of() : r.getData()));
        return out;
    }

    private String resolveCommandCode(String toolRef, Map<String, Object> args) {
        if (toolRef.startsWith("cmd_")) {
            return toolRef.substring(4);
        }
        if (args != null) {
            Object v = args.get("command_code");
            if (v instanceof String s) return s;
        }
        return null;
    }
}
