package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.PendingToolExecutionClaim;
import com.auraboot.framework.agent.runtime.PendingToolExecutionStatus;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;

import com.auraboot.framework.conversation.TurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingToolIntentIsolationTest {
    private final PendingToolSnapshotFactory factory = new PendingToolSnapshotFactory(new AgentRuntimeStateFactory());

    @Test
    void aNewUserTurnDoesNotReplayAnEarlierPermissionFailureForTheSameArguments() {
        ChatSessionStore store = new ChatSessionStore(new ObjectMapper(), null);
        PendingToolSnapshot denied = pending("denied-turn", "call-1");
        PendingToolExecutionClaim first = store.claimExecution(denied);
        store.failExecution(first.record().executionKey(), Map.of("success", false), "Permission denied");
        assertThat(store.claimExecution(denied).record().status()).isEqualTo(PendingToolExecutionStatus.FAILED);
        assertThat(store.claimExecution(pending("new-explicit-turn", "call-1")).acquired()).isTrue();
    }

    @Test
    void oneInvocationReplaysOnceButDistinctInvocationsDoNotShareSuccessfulReceipts() {
        ChatSessionStore store = new ChatSessionStore(new ObjectMapper(), null);
        PendingToolSnapshot original = pending("turn-1", "call-1");
        PendingToolExecutionClaim first = store.claimExecution(original);
        store.completeExecution(first.record().executionKey(), Map.of("success", true, "pid", "business-record"));
        assertThat(store.claimExecution(original).acquired()).isFalse();
        assertThat(store.claimExecution(original).record().status()).isEqualTo(PendingToolExecutionStatus.SUCCEEDED);
        assertThat(store.claimExecution(pending("turn-1", "call-2")).acquired()).isTrue();
    }

    @Test
    void retainedLegacySnapshotsKeepTheirOriginalReceiptLookupKey() {
        PendingToolSnapshot legacy = PendingToolSnapshot.builder().turnId("old-turn")
                .toolId("old-call").idempotencyKey("legacy-recorded-key").build();
        assertThat(PendingToolStore.executionKey(legacy)).isEqualTo("legacy-recorded-key");
    }

    @Test
    void aScopedReceiptCanBeRebuiltOnlyForItsOriginalInvocation() {
        PendingToolSnapshot original = pending("turn-1", "call-1");
        assertThat(pending("turn-1", "call-1", original.getIdempotencyKey()).getIdempotencyKey())
                .isEqualTo(original.getIdempotencyKey());
        assertThatThrownBy(() -> pending("another-turn", "call-1", original.getIdempotencyKey()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PendingToolSnapshot pending(String turnId, String toolId) {
        return pending(turnId, toolId, "tool:v1:same-argument-hash");
    }

    private PendingToolSnapshot pending(String turnId, String toolId, String semanticKey) {

        TurnContext ctx = new TurnContext(turnId, 1L, 100L, 200L, 300L,
                "agent-a", "web", 400L, 500L, null, Set.of(), "trace-1", "task-" + turnId,
                Instant.parse("2026-11-04T00:00:00Z"));
        return factory.build(PendingToolSnapshotFactory.Snapshot.builder().ctx(ctx).agentCode("agent-a")
                .sessionId("session-" + turnId).toolId(toolId).toolName("cmd_tpm_create_task")
                .input(Map.of("title", "Delivery review")).idempotencyKey(semanticKey)
                .build());
    }
}
