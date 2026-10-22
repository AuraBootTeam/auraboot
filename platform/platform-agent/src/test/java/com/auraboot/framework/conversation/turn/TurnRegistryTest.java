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
}
