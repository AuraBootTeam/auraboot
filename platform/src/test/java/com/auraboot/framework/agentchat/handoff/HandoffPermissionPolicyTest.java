package com.auraboot.framework.agentchat.handoff;

import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HandoffPermissionPolicy")
class HandoffPermissionPolicyTest {

    @Test
    @DisplayName("handoff decision uses source, inherited, and target permission intersection")
    void decide_intersectsSourceInheritedAndTargetPermissions() {
        AgentMemberDto source = AgentMemberDto.builder()
                .agentId(1L)
                .profilePermissions(Set.of("crm.customer.read", "crm.customer.write"))
                .build();
        AgentMemberDto target = AgentMemberDto.builder()
                .agentId(2L)
                .profilePermissions(Set.of("crm.customer.read", "finance.invoice.read"))
                .build();

        HandoffPermissionPolicy.Decision decision = HandoffPermissionPolicy.decide(
                source, target, Set.of("crm.customer.read", "crm.customer.write", "admin.root"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.effectivePermissions()).containsExactly("crm.customer.read");
        assertThat(decision.contextTransferPolicy())
                .isEqualTo(HandoffPermissionPolicy.ContextTransferPolicy.HANDOFF_CONTEXT_ONLY);
        assertThat(decision.stateTransferPolicy())
                .isEqualTo(HandoffPermissionPolicy.StateTransferPolicy.PARENT_TASK_ONLY);
        assertThat(decision.auditReason()).isEqualTo("permission_intersection");
    }

    @Test
    @DisplayName("handoff denies missing target instead of granting inherited permissions")
    void decide_deniesMissingTarget() {
        HandoffPermissionPolicy.Decision decision = HandoffPermissionPolicy.decide(
                AgentMemberDto.builder().agentId(1L).build(),
                null,
                Set.of("admin.root"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("target_not_allowed");
        assertThat(decision.effectivePermissions()).isEmpty();
    }
}
