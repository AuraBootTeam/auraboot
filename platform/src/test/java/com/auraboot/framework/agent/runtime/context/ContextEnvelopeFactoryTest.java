package com.auraboot.framework.agent.runtime.context;

import com.auraboot.framework.agent.identity.DelegationGrant;
import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.Initiator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextEnvelopeFactory")
class ContextEnvelopeFactoryTest {

    private final ContextEnvelopeFactory factory = new ContextEnvelopeFactory();

    @Test
    @DisplayName("same immutable execution inputs produce the same canonical hash")
    void canonicalHashIsStable() {
        Map<String, Object> firstSignals = new LinkedHashMap<>();
        firstSignals.put("batch", false);
        firstSignals.put("explicitDurableRequest", true);
        Map<String, Object> secondSignals = new LinkedHashMap<>();
        secondSignals.put("explicitDurableRequest", true);
        secondSignals.put("batch", false);

        ContextEnvelope first = compile(principal(301L), firstSignals);
        ContextEnvelope second = compile(principal(301L), secondSignals);

        assertThat(first.envelopeHash()).isNotBlank();
        assertThat(first.envelopeHash()).isEqualTo(second.envelopeHash());
        assertThat(first.agentReleasePid()).isEqualTo("AGENT_RELEASE_1");
        assertThat(first.deploymentPid()).isEqualTo("DEPLOYMENT_1");
        assertThat(first.agentReleaseHash()).isEqualTo("release-hash-1");
    }

    @Test
    @DisplayName("changing runtime actor changes the envelope hash")
    void actorIsPinnedByHash() {
        ContextEnvelope first = compile(principal(301L), Map.of());
        ContextEnvelope second = compile(principal(302L), Map.of());

        assertThat(first.envelopeHash()).isNotEqualTo(second.envelopeHash());
    }

    @Test
    @DisplayName("only declared routing signals enter the snapshot")
    void undeclaredAndSecretSignalsAreExcluded() {
        ContextEnvelope envelope = compile(
                principal(301L),
                Map.of(
                        "batch", true,
                        "apiKey", "must-not-enter-envelope",
                        "password", "must-not-enter-envelope"));

        assertThat(envelope.routeSignals()).containsEntry("batch", true);
        assertThat(envelope.routeSignals()).doesNotContainKeys("apiKey", "password");
        assertThat(envelope.toHashMaterial().toString())
                .doesNotContain("must-not-enter-envelope");
    }

    @Test
    @DisplayName("collections are defensive snapshots")
    void collectionsAreImmutableSnapshots() {
        List<String> knowledgeBaseIds =
                new java.util.ArrayList<>(List.of("KB_A", "KB_A", " KB_B "));
        ContextEnvelope envelope = factory.compile(new ContextEnvelopeFactory.CompileRequest(
                "TURN_1",
                principal(301L),
                "web",
                "PROFILE_1",
                "SESSION_1",
                91L,
                "CONTEXTUAL_ANSWER",
                Set.of("dsl.query"),
                knowledgeBaseIds,
                Map.of(),
                "zh-CN",
                "Asia/Shanghai",
                Instant.parse("2026-07-29T10:00:00Z")));

        knowledgeBaseIds.clear();

        assertThat(envelope.requestedKnowledgeBaseIds()).containsExactly("KB_A", "KB_B");
        assertThat(envelope.requestedKnowledgeBaseIds())
                .isUnmodifiable();
        assertThat(envelope.allowedReadOnlyTools()).isUnmodifiable();
    }

    @Test
    @DisplayName("persisted envelope hash verification rejects tampering")
    void persistedEnvelopeHashVerificationRejectsTampering() {
        ContextEnvelope original = compile(principal(301L), Map.of());
        ContextEnvelope tampered = new ContextEnvelope(
                original.schemaVersion(),
                original.turnId(),
                original.tenantId(),
                original.principal(),
                original.agentCode(),
                original.agentReleasePid(),
                original.deploymentPid(),
                original.agentReleaseHash(),
                "tampered-channel",
                original.profileId(),
                original.channelSessionPid(),
                original.conversationId(),
                original.triageBucket(),
                original.allowedReadOnlyTools(),
                original.requestedKnowledgeBaseIds(),
                original.capabilityCodes(),
                original.eligibleKnowledgeBaseIds(),
                original.memoryNamespaces(),
                original.policyVersions(),
                original.indexReleaseVersions(),
                original.routeSignals(),
                original.locale(),
                original.timezone(),
                original.traceId(),
                original.deadlineAt(),
                original.tokenBudget(),
                original.costBudgetMicros(),
                original.maxSteps(),
                original.idempotencyKey(),
                original.createdAt(),
                original.envelopeHash());

        assertThat(factory.verify(original)).isTrue();
        assertThat(factory.verify(tampered)).isFalse();
    }

    @Test
    @DisplayName("v2 pins memory namespaces, runtime limits and secret-free policy versions")
    void runtimeGovernanceInputsArePinned() {
        ContextEnvelope envelope = compile(
                principal(301L),
                Map.of(
                        "traceId", "trace-123",
                        "deadlineMs", 60_000,
                        "tokenBudget", 4_096,
                        "costBudgetMicros", 125_000,
                        "maxSteps", 12,
                        "idempotencyKey", "turn-123"));

        assertThat(envelope.schemaVersion()).isEqualTo("context-envelope/v2");
        assertThat(envelope.memoryNamespaces())
                .containsEntry("working", "turn:TURN_1")
                .containsEntry("employee", "employee:501")
                .containsEntry("conversation", "conversation:91")
                .containsEntry("session", "session:SESSION_1");
        assertThat(envelope.policyVersions())
                .containsEntry("agentRelease", "release-hash-1")
                .containsEntry("deployment", "DEPLOYMENT_1")
                .containsEntry("riskScale", "risk-scale/v1")
                .containsEntry("invocationPolicy", "invocation-policy/v1");
        assertThat(envelope.traceId()).isEqualTo("trace-123");
        assertThat(envelope.deadlineAt())
                .isEqualTo(Instant.parse("2026-07-29T10:01:00Z"));
        assertThat(envelope.tokenBudget()).isEqualTo(4_096L);
        assertThat(envelope.costBudgetMicros()).isEqualTo(125_000L);
        assertThat(envelope.maxSteps()).isEqualTo(12);
        assertThat(envelope.idempotencyKey()).isEqualTo("turn-123");
        assertThat(factory.verify(envelope)).isTrue();
    }

    private ContextEnvelope compile(
            ExecutionPrincipal principal, Map<String, Object> signals) {
        return factory.compile(new ContextEnvelopeFactory.CompileRequest(
                "TURN_1",
                principal,
                "web",
                "PROFILE_1",
                "SESSION_1",
                91L,
                "ACP_RUN",
                Set.of("dsl.query"),
                List.of("KB_A"),
                signals,
                "zh-CN",
                "Asia/Shanghai",
                Instant.parse("2026-07-29T10:00:00Z")));
    }

    private ExecutionPrincipal principal(long actorUserId) {
        return new ExecutionPrincipal(
                7L,
                actorUserId,
                401L,
                "USR_AGENT_" + actorUserId,
                "agent-" + actorUserId,
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
    }
}
