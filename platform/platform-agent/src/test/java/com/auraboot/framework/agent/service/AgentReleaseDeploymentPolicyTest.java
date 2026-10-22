package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentReleaseDeploymentService invocation policy")
class AgentReleaseDeploymentPolicyTest {

    @Test
    @DisplayName("normalizes policy inputs and persists only the versioned allowlist")
    void normalizesAndPersistsPolicy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentReleaseDeploymentService service =
                new AgentReleaseDeploymentService(jdbc, new ObjectMapper());
        when(jdbc.update(
                anyString(),
                any(),
                any(),
                anyLong(),
                anyString())).thenReturn(1);
        when(jdbc.queryForList(
                anyString(),
                anyLong(),
                anyString())).thenReturn(List.of(Map.of(
                        "deployment_pid", "DEPLOYMENT_1",
                        "channel_policy",
                        """
                        {"version":"invocation-policy/v1",
                         "allowedChannels":["schedule","web"],
                         "allowedInitiatorTypes":["human"],
                         "allowedUserIds":[101],
                         "allowedMemberIds":[],
                         "allowedRoleIds":[44,55]}
                        """,
                        "policy_snapshot",
                        "{\"invocationPolicyVersion\":\"invocation-policy/v1\"}")));

        AgentReleaseDeploymentService.DeploymentPolicy result =
                service.updateDeploymentPolicy(
                        7L,
                        "AGENT_1",
                        Map.of(
                                "allowedChannels",
                                List.of("WEB", "schedule", "unknown"),
                                "allowedInitiatorTypes",
                                List.of("human", "INVALID"),
                                "allowedUserIds",
                                List.of("101", -2, "bad"),
                                "allowedRoleIds",
                                List.of(55, 44, 44)),
                        9L);

        ArgumentCaptor<Object> policyJson = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(
                anyString(),
                policyJson.capture(),
                any(),
                anyLong(),
                anyString());
        assertThat(String.valueOf(policyJson.getValue()))
                .contains("\"version\":\"invocation-policy/v1\"")
                .contains("\"allowedChannels\":[\"schedule\",\"web\"]")
                .contains("\"allowedInitiatorTypes\":[\"human\"]")
                .contains("\"allowedUserIds\":[101]")
                .contains("\"allowedRoleIds\":[44,55]")
                .doesNotContain("unknown")
                .doesNotContain("INVALID")
                .doesNotContain("bad");
        assertThat(result.deploymentPid()).isEqualTo("DEPLOYMENT_1");
        assertThat(result.channelPolicy())
                .containsEntry("version", "invocation-policy/v1");
    }
}
