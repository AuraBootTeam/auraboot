package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link InterruptDispatcher} tenant-scoping of the agent-run cancel path
 * (deep-review DR-20260701 W1-F1).
 *
 * <p>A REPLACE_INTENT interrupt cancels the active run via a raw JdbcTemplate UPDATE that
 * bypasses the MyBatis tenant interceptor ({@code ab_agent_run} is not in the ignoreTable),
 * so the caller's tenant_id MUST be in the WHERE clause — otherwise any authenticated user
 * could cancel another tenant's running agent run by submitting its run pid.
 */
@ExtendWith(MockitoExtension.class)
class InterruptDispatcherTenantScopeTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RunLifecycleService runLifecycleService;
    @Mock private SubAgentRunner subAgentRunner;

    @InjectMocks private InterruptDispatcher dispatcher;

    @Test
    @DisplayName("REPLACE_INTENT cancel UPDATE is tenant-scoped (WHERE tenant_id = ? bound to caller tenant)")
    void cancelRunUpdateIsTenantScoped() {
        InterruptClassifier.Classification replace = InterruptClassifier.Classification.builder()
                .subPolicy(InterruptClassifier.REPLACE_INTENT)
                .confidence(0.9)
                .tier("keyword")
                .reason("test")
                .build();

        // jdbcTemplate.update(...) returns 0 by default → cancelRun treats it as a race with completion
        // and returns before the follow-up SELECT, so no further stubbing is needed.
        dispatcher.dispatch(7L, "session-1", "run-from-tenant-B", "stop", replace);

        // The cancel UPDATE must be tenant-scoped and bind (runPid, tenantId) — not runPid alone.
        verify(jdbcTemplate).update(
                argThat((String sql) -> sql != null
                        && sql.contains("UPDATE ab_agent_run")
                        && sql.contains("tenant_id = ?")),
                eq("run-from-tenant-B"), eq(7L));
    }
}
