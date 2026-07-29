package com.auraboot.framework.agent.identity;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExecutionPrincipalContext")
class ExecutionPrincipalContextTest {

    @AfterEach
    void cleanUp() {
        ExecutionPrincipalContext.clear();
        MetaContext.clear();
    }

    @Test
    @DisplayName("runtime sees employee authority while caller identity is restored afterwards")
    void switchesAndRestoresMetaContext() {
        MetaContext.setContext(7L, 101L, "USR_HUMAN", "human", Set.of(21L));
        MetaContext.setMemberId(201L);
        MetaContext.setEnvironmentId(51L);
        MetaContext.setOtelTraceId("trace-principal");
        ExecutionPrincipal principal = new ExecutionPrincipal(
                7L,
                301L,
                401L,
                "USR_AGENT",
                "agent-sales",
                501L,
                "EMP_SALES",
                Initiator.human(101L, 201L, "web"),
                DelegationGrant.employeeAutonomous(),
                "sales_colleague",
                "AGENT_RELEASE_1",
                "DEPLOYMENT_1",
                "release-hash-1",
                "web",
                ExecutionPrincipal.Type.DIGITAL_EMPLOYEE,
                Set.of(11L, 12L));

        Long observedUserId = ExecutionPrincipalContext.callAs(principal, () -> {
            assertThat(ExecutionPrincipalContext.requireCurrent()).isEqualTo(principal);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(401L);
            assertThat(MetaContext.getCurrentRoleIds()).containsExactlyInAnyOrder(11L, 12L);
            assertThat(MetaContext.getCurrentEnvironmentId()).isEqualTo(51L);
            assertThat(MetaContext.getOtelTraceId()).isEqualTo("trace-principal");
            return MetaContext.getCurrentUserId();
        });

        assertThat(observedUserId).isEqualTo(301L);
        assertThat(ExecutionPrincipalContext.current()).isEmpty();
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(101L);
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(201L);
        assertThat(MetaContext.getCurrentRoleIds()).containsExactly(21L);
        assertThat(MetaContext.getCurrentEnvironmentId()).isEqualTo(51L);
        assertThat(MetaContext.getOtelTraceId()).isEqualTo("trace-principal");
    }
}
