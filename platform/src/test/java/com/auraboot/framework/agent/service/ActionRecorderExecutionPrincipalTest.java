package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.identity.DelegationGrant;
import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.identity.Initiator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActionRecorder execution identity")
class ActionRecorderExecutionPrincipalTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private FidelityGrader fidelityGrader;

    @AfterEach
    void cleanUp() {
        ExecutionPrincipalContext.clear();
    }

    @Test
    @DisplayName("action row separates employee actor from human initiator")
    void stampsActorAndInitiatorSeparately() {
        when(fidelityGrader.grade(any())).thenReturn("semantic");
        ActionRecorder recorder =
                new ActionRecorder(dynamicDataMapper, new ObjectMapper(), fidelityGrader);
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
                Set.of(11L));

        String actionPid = ExecutionPrincipalContext.callAs(
                principal,
                () -> recorder.recordProviderAction(
                        7L,
                        "RUN_1",
                        "crm.list",
                        null,
                        Map.of("modelCode", "crm_account_common"),
                        Map.of("records", java.util.List.of()),
                        null,
                        Set.of(EffectClass.READ_PLATFORM_DATA)));

        assertThat(actionPid).isNotBlank();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> row =
                ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).insertWithJsonb(
                eq("ab_agent_action"), row.capture(), any());
        assertThat(row.getValue())
                .containsEntry("actor_id", "sales_colleague")
                .containsEntry("actor_user_id", 301L)
                .containsEntry("actor_member_id", 401L)
                .containsEntry("initiator_user_id", 101L)
                .containsEntry("initiator_member_id", 201L)
                .containsEntry("on_behalf_of_user_id", 101L)
                .containsEntry("agent_release_pid", "AGENT_RELEASE_1")
                .containsEntry("deployment_pid", "DEPLOYMENT_1")
                .containsEntry("principal_type", "digital_employee");
    }
}
