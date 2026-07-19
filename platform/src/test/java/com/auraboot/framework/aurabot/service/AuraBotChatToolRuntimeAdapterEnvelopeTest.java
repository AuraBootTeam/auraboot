package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.conversation.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuraBotChatToolRuntimeAdapter#capForReadOnlyVerdict}
 * — the G10 wiring that makes the triage "read-only tier" real. Before this,
 * {@code TriageVerdict.allowedReadOnlyTools} had zero consumers: the label
 * existed, the enforcement did not.
 *
 * <p>Load-bearing property: the verdict is a CAP. It may tighten a
 * write-capable envelope to read-only; it must never loosen an already
 * tighter envelope (answer-only stays answer-only).
 */
@DisplayName("AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict — G10 read-only tier enforcement")
class AuraBotChatToolRuntimeAdapterEnvelopeTest {

    private TurnContext ctx(TriageBucket bucket, Set<String> readOnlyTools) {
        return new TurnContext(
                "01HW3KTEST",
                42L,
                100L,
                200L,
                null,                                // agentId
                null,                                // agentCode
                null,                                // channelSessionId
                999L,                                // conversationId
                null,                                // inboundMessageId
                bucket,
                readOnlyTools,
                null,                                // traceId
                null,                                // taskPid
                Instant.now());
    }

    @Test
    @DisplayName("read-only verdict caps a write-capable envelope down to read-only catalog")
    void readOnlyVerdict_capsWriteEnvelope() {
        ExecutionEnvelope capped = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("schema.lookup", "record.view")));

        assertThat(capped).isEqualTo(ExecutionEnvelope.readOnlyCatalog());
    }

    @Test
    @DisplayName("cap never loosens: answer-only stays answer-only under a read-only verdict")
    void readOnlyVerdict_neverLoosensAnswerOnly() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.answerOnly(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("record.view")));

        assertThat(kept).isEqualTo(ExecutionEnvelope.answerOnly());
    }

    @Test
    @DisplayName("read-only envelope stays read-only (idempotent)")
    void readOnlyVerdict_idempotentOnReadOnly() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.readOnlyCatalog(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("record.view")));

        assertThat(kept).isEqualTo(ExecutionEnvelope.readOnlyCatalog());
    }

    @Test
    @DisplayName("non-contextual bucket does not cap: LIGHT_CHAT keeps its write envelope")
    void lightChat_notCapped() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of()));

        assertThat(kept).isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
    }

    @Test
    @DisplayName("contextual bucket WITHOUT granted read-only tools does not cap")
    void contextualWithoutTools_notCapped() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of()));

        assertThat(kept).isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
    }

    @Test
    @DisplayName("null ctx / null base are passed through untouched")
    void nullSafety() {
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(), null))
                .isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                null, ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("record.view"))))
                .isNull();
    }
}
