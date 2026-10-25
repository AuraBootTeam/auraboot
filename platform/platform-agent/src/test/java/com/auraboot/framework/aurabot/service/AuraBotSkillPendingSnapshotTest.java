package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.conversation.TurnContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuraBotSkillPendingSnapshotTest {
    private final PendingToolSnapshotFactory snapshots = mock(PendingToolSnapshotFactory.class);
    private final PendingToolStore store = mock(PendingToolStore.class);
    private final AuraBotChatToolRuntimeAdapter adapter = new AuraBotChatToolRuntimeAdapter(
            null, null, mock(ChatToolResolver.class), null, null, store, snapshots, null,
            new ReadOnlyProfilePolicy(), 3, null, null);
    private final TurnContext ctx = new TurnContext("turn", 7L, 9L, 11L, null, "aurabot",
            "web", null, null, 13L, null, TriageBucket.SYNC_ACTION, Set.of(), null, null, Instant.now());

    @Test void firstSkillProposalPreservesPreviewTokenForConfirmedExecution() {
        callbacks().storeToolResultConfirmationPending(pending("aurabot_model_create"), Map.of(
                "approvalRequired", true, "previewToken", "preview-token", "riskLevel", "high",
                "preview", Map.of("code", "native_snapshot")));
        var snapshot = captured();
        assertThat(snapshot.getExtension()).containsEntry("_aurabot_skill", true)
                .containsEntry("previewToken", "preview-token")
                .containsEntry("riskLevel", "high")
                .containsEntry("preview", Map.of("code", "native_snapshot"));
        assertThat(snapshot.getToolId()).isEqualTo("tool-id");
        assertThat(snapshot.getInput()).containsEntry("code", "native_snapshot");
    }

    @Test void ordinaryConfirmationDoesNotAcquireASkillPreviewToken() {
        callbacks().storeToolResultConfirmationPending(pending("cmd_create_record"), Map.of("success", false));
        assertThat(captured().getExtension()).isNullOrEmpty();
    }

    private PendingToolSnapshotFactory.Snapshot captured() {
        var captor = ArgumentCaptor.forClass(PendingToolSnapshotFactory.Snapshot.class);
        verify(snapshots).build(captor.capture());
        return captor.getValue();
    }

    private ChatTurnRuntime.ChatToolLoopCallbacks callbacks() {
        return ReflectionTestUtils.invokeMethod(adapter, "callbacks", ctx, null, "session", null,
                ExecutionEnvelope.writeCatalogWithGate(), "Create a model");
    }

    private ChatTurnRuntime.PendingChatTool pending(String tool) {
        return new ChatTurnRuntime.PendingChatTool(ctx, "aurabot", "session", "tool-id", tool,
                Map.of("code", "native_snapshot"), "v1", "args-hash", "idem", Instant.now().plusSeconds(30),
                "user_confirmation", "schema-hash", "preview", List.of(), List.of(), List.of(),
                "deepseek", "deepseek-flash", "system", "runtime", 100, 0, "auto", false);
    }
}
