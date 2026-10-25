package com.auraboot.framework.conversation.turn;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TurnRegistryTest {

    @Test
    void registerAndGet() {
        TurnRegistry reg = new TurnRegistry();
        TurnHandle h = reg.register("t1", 88L, 100L, "Aurabot", 11L, 42L);
        assertThat(reg.get("t1")).contains(h);
        assertThat(h.getStatus()).isEqualTo(TurnStatus.ACTIVE);
        assertThat(h.getInitiatorUserId()).isEqualTo(11L);
        assertThat(h.getCancelled().get()).isFalse();
    }

    @Test
    void getOnUnknownTurnReturnsEmpty() {
        TurnRegistry reg = new TurnRegistry();
        assertThat(reg.get("bogus")).isEmpty();
    }

    @Test
    void markCompletedChangesStatus() {
        TurnRegistry reg = new TurnRegistry();
        reg.register("t1", 88L, 100L, "A", 11L, null);
        reg.markCompleted("t1");
        assertThat(reg.get("t1").orElseThrow().getStatus()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void markFailedAndCancelled() {
        TurnRegistry reg = new TurnRegistry();
        reg.register("t1", 88L, 100L, "A", 11L, null);
        reg.register("t2", 88L, 100L, "A", 11L, null);
        reg.markFailed("t1");
        reg.markCancelled("t2");
        assertThat(reg.get("t1").orElseThrow().getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(reg.get("t2").orElseThrow().getStatus()).isEqualTo(TurnStatus.CANCELLED);
    }

    @Test
    void getActiveByConversationFiltersTerminal() {
        TurnRegistry reg = new TurnRegistry();
        reg.register("t1", 88L, 100L, "A", 11L, null);
        reg.register("t2", 88L, 100L, "A", 11L, null);
        reg.register("t3", 99L, 100L, "A", 11L, null);
        reg.markCompleted("t1");
        assertThat(reg.getActiveByConversation(88L)).extracting(TurnHandle::getTurnId)
                .containsExactly("t2");
        assertThat(reg.getActiveByConversation(99L)).extracting(TurnHandle::getTurnId)
                .containsExactly("t3");
    }

    @Test
    void cumulativeBufferAppendAndRead() {
        TurnRegistry reg = new TurnRegistry();
        TurnHandle h = reg.register("t1", 88L, 100L, "A", 11L, null);
        h.appendCumulative("Hello ");
        h.appendCumulative("world");
        assertThat(h.getCumulative()).isEqualTo("Hello world");
    }

    @Test
    void markCancelledFlipsCancelledFlag() {
        TurnRegistry reg = new TurnRegistry();
        TurnHandle h = reg.register("t1", 88L, 100L, "A", 11L, null);
        reg.markCancelled("t1");
        assertThat(h.getCancelled().get()).isTrue();
        assertThat(h.getStatus()).isEqualTo(TurnStatus.CANCELLED);
    }
    @Test
    void cancellationCannotBeOverwrittenByLateCompletionOrFailure() {
        TurnRegistry registry = new TurnRegistry();
        TurnHandle handle = registry.register("cancelled", 88L, 100L, "A", 11L, null);
        registry.markCancelled("cancelled");
        registry.markCompleted("cancelled");
        registry.markFailed("cancelled");
        assertThat(handle.getStatus()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(handle.getCancelled()).isTrue();
    }

    @Test
    void lateCancellationDoesNotRewriteAnAlreadyCompletedOrFailedTurn() {
        TurnRegistry registry = new TurnRegistry();
        for (boolean completed : new boolean[] {true, false}) {
            String id = "terminal-" + completed;
            TurnHandle handle = registry.register(id, 88L, 100L, "A", 11L, null);
            if (completed) registry.markCompleted(id); else registry.markFailed(id);
            registry.markCancelled(id);
            assertThat(handle.getStatus()).isEqualTo(completed ? TurnStatus.COMPLETED : TurnStatus.FAILED);
            assertThat(handle.getCancelled()).isFalse();
        }
    }

    @Test
    void concurrentTerminalSignalsHaveOneStableWinner() throws Exception {
        TurnRegistry registry = new TurnRegistry();
        TurnHandle handle = registry.register("race", 88L, 100L, "A", 11L, null);
        var barrier = new java.util.concurrent.CyclicBarrier(3);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var completed = executor.submit(() -> { barrier.await(); registry.markCompleted("race"); return null; });
            var cancelled = executor.submit(() -> { barrier.await(); registry.markCancelled("race"); return null; });
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            cancelled.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        TurnStatus winner = handle.getStatus();
        assertThat(winner).isIn(TurnStatus.COMPLETED, TurnStatus.CANCELLED);
        registry.markFailed("race");
        assertThat(handle.getStatus()).isEqualTo(winner);
        assertThat(handle.getCancelled().get()).isEqualTo(winner == TurnStatus.CANCELLED);
        assertThat(registry.getActiveByConversation(88L)).isEmpty();
    }


    @Test void terminalRetentionIsBoundedAndNeverEvictsActiveTurns() {
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var registry = new TurnRegistry(1000, 2, nanos::get);
        var active = registry.register("active", 88L, 1L, "A", 1L, null);
        for (int i = 0; i < 8; i++) {
            var handle = registry.register("terminal-" + i, 88L, 1L, "A", 1L, null);
            handle.appendCumulative("sensitive generated text");
            registry.markCompleted(handle.getTurnId());
            handle.appendCumulative("late chunk");
            assertThat(handle.getCumulative()).isEmpty();
        }
        registry.cleanup();
        long retained = java.util.stream.IntStream.range(0, 8)
                .filter(i -> registry.get("terminal-" + i).isPresent()).count();
        assertThat(retained).isLessThanOrEqualTo(2);
        nanos.set(java.time.Duration.ofSeconds(2).toNanos());
        registry.cleanup();
        assertThat(java.util.stream.IntStream.range(0, 8)
                .filter(i -> registry.get("terminal-" + i).isPresent()).count()).isZero();
        assertThat(registry.get("active")).contains(active);
        assertThat(registry.getActiveByConversation(88L)).containsExactly(active);
    }
}
