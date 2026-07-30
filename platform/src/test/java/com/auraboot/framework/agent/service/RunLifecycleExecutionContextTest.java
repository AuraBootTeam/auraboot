package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.identity.DelegationGrant;
import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.identity.Initiator;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.context.ContextEnvelope;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunLifecycle execution context")
class RunLifecycleExecutionContextTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AgentMemoryService memoryService;
    @Mock private AgentObservationService observationService;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;

    @AfterEach
    void cleanUp() {
        ContextEnvelopeContext.clear();
        ExecutionPrincipalContext.clear();
    }

    @Test
    @DisplayName("new durable run pins actor, initiator, release and envelope hash")
    void runRecordPinsExecutionContext() {
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
        ContextEnvelope envelope = new ContextEnvelopeFactory().compile(
                new ContextEnvelopeFactory.CompileRequest(
                        "TURN_1",
                        principal,
                        "web",
                        "PROFILE_1",
                        "SESSION_1",
                        91L,
                        "ACP_RUN",
                        Set.of(),
                        List.of("KB_A"),
                        Map.of("durable", true),
                        "zh-CN",
                        "Asia/Shanghai",
                        Instant.parse("2026-07-29T10:00:00Z")));
        RunLifecycleService service = new RunLifecycleService(
                dynamicDataMapper,
                new ObjectMapper().findAndRegisterModules(),
                memoryService,
                observationService,
                providerFactory,
                jdbcTemplate,
                eventPublisher);

        ExecutionPrincipalContext.callAs(
                principal,
                () -> ContextEnvelopeContext.callWith(
                        envelope,
                        () -> {
                            service.createRunRecord(
                                    7L,
                                    "RUN_1",
                                    "TASK_1",
                                    "sales_colleague",
                                    "model-profile",
                                    LocalDateTime.parse("2026-07-29T10:00:00"));
                            return null;
                        }));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> row =
                ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).insert(eq("ab_agent_run"), row.capture());
        assertThat(row.getValue())
                .containsEntry("actor_user_id", 301L)
                .containsEntry("actor_member_id", 401L)
                .containsEntry("initiator_user_id", 101L)
                .containsEntry("initiator_member_id", 201L)
                .containsEntry("agent_release_pid", "AGENT_RELEASE_1")
                .containsEntry("deployment_pid", "DEPLOYMENT_1")
                .containsEntry("principal_type", "digital_employee")
                .containsEntry("context_envelope_hash", envelope.envelopeHash());
        assertThat(row.getValue().get("context_envelope").toString())
                .contains("\"schemaVersion\":\"context-envelope/v2\"")
                .contains("\"agentReleasePid\":\"AGENT_RELEASE_1\"")
                .contains("\"deploymentPid\":\"DEPLOYMENT_1\"");
    }
}
