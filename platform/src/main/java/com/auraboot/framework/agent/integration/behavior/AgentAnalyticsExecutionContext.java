package com.auraboot.framework.agent.integration.behavior;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.service.StepContext;
import com.auraboot.framework.conversation.TurnScopeContext;
import com.auraboot.framework.behavior.service.AnalyticsExecutionContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Projects existing execution contexts without changing their scope or identity. */
@Component
public class AgentAnalyticsExecutionContext implements AnalyticsExecutionContext {
    @Override public Optional<Principal> principal() {
        return ExecutionPrincipalContext.current().map(p ->
                new Principal(p.type() == ExecutionPrincipal.Type.SANDBOX, p.tenantId(), p.actorUserId()));
    }
    @Override public boolean isAgentExecution() {
        return ExecutionPrincipalContext.current().isPresent() || TurnScopeContext.get() != null || StepContext.getRunPid() != null;
    }
    @Override public String runPid() { return StepContext.getRunPid(); }
}
