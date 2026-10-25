package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolExecutionClaim;
import com.auraboot.framework.agent.runtime.PendingToolExecutionLedger;
import com.auraboot.framework.agent.runtime.PendingToolExecutionRecord;
import com.auraboot.framework.agent.runtime.PendingToolExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionStore reliability")
class ChatSessionStoreReliabilityTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private PendingToolExecutionLedger executionLedger;

    private ChatSessionStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new ChatSessionStore(new ObjectMapper(), redisTemplate);
    }

    @Test
    @DisplayName("storePending fails closed when Redis write fails")
    void storePendingFailsClosedWhenRedisWriteFails() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations)
                .set(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES));

        assertThatThrownBy(() -> store.storePending("turn-1", pending()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pending tool storage unavailable")
                .hasRootCauseMessage("redis down");
    }

    @Test
    @DisplayName("consumePendingForOwner fails closed when Redis read fails")
    void consumePendingForOwnerFailsClosedWhenRedisReadFails() {
        when(redisTemplate.execute(any(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> store.consumePendingForOwner("turn-1", 1L, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pending tool storage unavailable")
                .hasRootCauseMessage("redis down");
    }

    @Test
    @DisplayName("consumePendingForOwner does not delete pending when owner does not match")
    void consumePendingForOwnerDoesNotDeleteOnOwnerMismatch() {
        ChatSessionStore localStore = new ChatSessionStore(new ObjectMapper(), null);
        localStore.storePending("turn-1", pending());

        PendingToolSnapshot mismatch = localStore.consumePendingForOwner("turn-1", 1L, 999L);
        PendingToolSnapshot owner = localStore.consumePendingForOwner("turn-1", 1L, 2L);

        assertThat(mismatch).isNull();
        assertThat(owner).isNotNull();
        assertThat(owner.getToolId()).isEqualTo("tool-1");
    }

    @Test
    @DisplayName("consumePendingForOwner rejects expired pending snapshots in local mode")
    void consumePendingForOwnerRejectsExpiredSnapshot() {
        ChatSessionStore localStore = new ChatSessionStore(new ObjectMapper(), null);
        localStore.storePending("turn-expired", pendingWithExpiresAt(System.currentTimeMillis() - 1));

        PendingToolSnapshot expired = localStore.consumePendingForOwner("turn-expired", 1L, 2L);

        assertThat(expired).isNull();
    }

    @Test
    @DisplayName("storePending uses snapshot expiresAt as Redis TTL when present")
    void storePendingUsesSnapshotExpiresAtForRedisTtl() {
        store.storePending("turn-short", pendingWithExpiresAt(System.currentTimeMillis() + 5_000L));

        verify(valueOperations).set(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("local execution records claim once and replay completed result")
    void localExecutionRecordsClaimOnceAndReplayCompletedResult() {
        ChatSessionStore localStore = new ChatSessionStore(new ObjectMapper(), null);
        PendingToolSnapshot pending = pending();
        pending.setIdempotencyKey("idem-1");

        PendingToolExecutionClaim first = localStore.claimExecution(pending);
        PendingToolExecutionClaim second = localStore.claimExecution(pending);
        localStore.completeExecution(first.record().executionKey(), Map.of("success", true, "pid", "model-1"));
        PendingToolExecutionClaim replay = localStore.claimExecution(pending);

        assertThat(first.acquired()).isTrue();
        assertThat(first.record().status()).isEqualTo(PendingToolExecutionStatus.RUNNING);
        assertThat(second.acquired()).isFalse();
        assertThat(second.record().status()).isEqualTo(PendingToolExecutionStatus.RUNNING);
        assertThat(replay.acquired()).isFalse();
        assertThat(replay.record().status()).isEqualTo(PendingToolExecutionStatus.SUCCEEDED);
        assertThat(replay.record().result()).containsEntry("pid", "model-1");
    }

    @Test
    @DisplayName("durable execution ledger is used before Redis execution records")
    void durableExecutionLedgerIsUsedBeforeRedisExecutionRecords() {
        ChatSessionStore durableStore = new ChatSessionStore(new ObjectMapper(), redisTemplate, executionLedger);
        PendingToolSnapshot pending = pending();
        PendingToolExecutionClaim replay = PendingToolExecutionClaim.replay(
                PendingToolExecutionRecord.succeeded("ledger-key", Map.of("pid", "model-1")));
        when(executionLedger.claim(pending)).thenReturn(replay);

        PendingToolExecutionClaim claim = durableStore.claimExecution(pending);
        durableStore.completeExecution(pending, "ledger-key", Map.of("pid", "model-1"));
        durableStore.failExecution(pending, "ledger-key", Map.of("success", false), "boom");

        assertThat(claim).isSameAs(replay);
        verify(executionLedger).claim(pending);
        verify(executionLedger).complete(pending, "ledger-key", Map.of("pid", "model-1"));
        verify(executionLedger).fail(pending, "ledger-key", Map.of("success", false), "boom");
    }

    @Test
    @DisplayName("storeConversationMessages fails closed when Redis write fails")
    void storeConversationMessagesFailsClosedWhenRedisWriteFails() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations)
                .set(anyString(), anyString(), eq(60L), eq(TimeUnit.MINUTES));

        assertThatThrownBy(() -> store.storeConversationMessages("session-1", List.of(Map.of("role", "user"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chat message tape storage unavailable")
                .hasRootCauseMessage("redis down");
    }

    @Test
    @DisplayName("loadConversationMessages fails closed when Redis read fails")
    void loadConversationMessagesFailsClosedWhenRedisReadFails() {
        when(valueOperations.get(anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> store.loadConversationMessages("session-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chat message tape storage unavailable")
                .hasRootCauseMessage("redis down");
    }

    private PendingToolSnapshot pending() {
        return PendingToolSnapshot.builder()
                .turnId("turn-1")
                .tenantId(1L)
                .userId(2L)
                .toolId("tool-1")
                .toolName("Tool")
                .input(Map.of("ok", true))
                .build();
    }

    private PendingToolSnapshot pendingWithExpiresAt(long expiresAt) {
        return PendingToolSnapshot.builder()
                .turnId("turn-1")
                .tenantId(1L)
                .userId(2L)
                .toolId("tool-1")
                .toolName("Tool")
                .input(Map.of("ok", true))
                .expiresAt(expiresAt)
                .build();
    }
}
