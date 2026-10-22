package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.conversation.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuraBotChatToolRuntimeAdapter#capForReadOnlyVerdict}
 * — the G10 wiring that makes the triage "read-only tier" real, plus the D2
 * profile-based hard cap ({@code aurabot.policy.read-only-profiles}).
 *
 * <p>Load-bearing property: both inputs are CAPS. They may tighten a
 * write-capable envelope to read-only; they must never loosen an already
 * tighter envelope (answer-only stays answer-only).
 */
@DisplayName("AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict — G10 verdict cap + D2 profile cap")
class AuraBotChatToolRuntimeAdapterEnvelopeTest {

    private final ReadOnlyProfilePolicy defaultPolicy = new ReadOnlyProfilePolicy();

    private TurnContext ctx(TriageBucket bucket, Set<String> readOnlyTools) {
        return ctx(bucket, readOnlyTools, null);
    }

    private TurnContext ctx(TriageBucket bucket, Set<String> readOnlyTools, String profileId) {
        return new TurnContext(
                "01HW3KTEST",
                42L,
                100L,
                200L,
                null,                                // agentId
                null,                                // agentCode
                "web",                               // channel
                profileId,
                null,                                // channelSessionId
                999L,                                // conversationId
                null,                                // inboundMessageId
                bucket,
                readOnlyTools,
                null,                                // traceId
                null,                                // taskPid
                Instant.now());
    }

    // =====================================================================
    // G10 — triage read-only verdict cap
    // =====================================================================

    @Test
    @DisplayName("read-only verdict caps a write-capable envelope down to read-only catalog")
    void readOnlyVerdict_capsWriteEnvelope() {
        ExecutionEnvelope capped = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("schema.lookup", "record.view")),
                defaultPolicy);

        assertThat(capped).isEqualTo(ExecutionEnvelope.readOnlyCatalog());
    }

    @Test
    @DisplayName("cap never loosens: answer-only stays answer-only under a read-only verdict")
    void readOnlyVerdict_neverLoosensAnswerOnly() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.answerOnly(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("record.view")),
                defaultPolicy);

        assertThat(kept).isEqualTo(ExecutionEnvelope.answerOnly());
    }

    @Test
    @DisplayName("read-only envelope stays read-only (idempotent)")
    void readOnlyVerdict_idempotentOnReadOnly() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.readOnlyCatalog(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("record.view")),
                defaultPolicy);

        assertThat(kept).isEqualTo(ExecutionEnvelope.readOnlyCatalog());
    }

    @Test
    @DisplayName("non-contextual bucket without read-only profile does not cap")
    void lightChat_notCapped() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of()),
                defaultPolicy);

        assertThat(kept).isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
    }

    @Test
    @DisplayName("contextual bucket WITHOUT granted read-only tools does not cap")
    void contextualWithoutTools_notCapped() {
        ExecutionEnvelope kept = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of()),
                defaultPolicy);

        assertThat(kept).isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
    }

    // =====================================================================
    // D2 — read-only profile hard cap (owner-approved 2026-07-19)
    // =====================================================================

    @Test
    @DisplayName("D2: support_chat profile caps a write envelope even on a LIGHT_CHAT/SYNC_ACTION bucket")
    void readOnlyProfile_capsRegardlessOfBucket() {
        // The whole point: routing (or a routing bug) must not matter — the
        // profile alone forces the tool scope down.
        for (TriageBucket bucket : new TriageBucket[] {
                TriageBucket.LIGHT_CHAT, TriageBucket.SYNC_ACTION, TriageBucket.CONTEXTUAL_ANSWER}) {
            ExecutionEnvelope capped = AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                    ExecutionEnvelope.writeCatalogWithGate(),
                    ctx(bucket, Set.of(), "support_chat"),
                    defaultPolicy);
            assertThat(capped).as("bucket: %s", bucket).isEqualTo(ExecutionEnvelope.readOnlyCatalog());
        }
    }

    @Test
    @DisplayName("D2: profile matching is case-insensitive and configurable")
    void readOnlyProfile_configurableAndCaseInsensitive() {
        ReadOnlyProfilePolicy custom = new ReadOnlyProfilePolicy(List.of("Support_Chat", "kiosk"));
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of(), "KIOSK"),
                custom))
                .isEqualTo(ExecutionEnvelope.readOnlyCatalog());
    }

    @Test
    @DisplayName("D2: unrelated / null profile does not cap")
    void otherProfile_notCapped() {
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of(), "sales_ops"),
                defaultPolicy))
                .isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of(), null),
                defaultPolicy))
                .isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
    }

    @Test
    @DisplayName("D2: profile cap never loosens answer-only either")
    void readOnlyProfile_neverLoosens() {
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.answerOnly(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of(), "support_chat"),
                defaultPolicy))
                .isEqualTo(ExecutionEnvelope.answerOnly());
    }

    // =====================================================================
    // Null safety
    // =====================================================================

    @Test
    @DisplayName("null ctx / null base / null policy are passed through untouched")
    void nullSafety() {
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(), null, defaultPolicy))
                .isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                null, ctx(TriageBucket.CONTEXTUAL_ANSWER, Set.of("record.view")), defaultPolicy))
                .isNull();
        assertThat(AuraBotChatToolRuntimeAdapter.capForReadOnlyVerdict(
                ExecutionEnvelope.writeCatalogWithGate(),
                ctx(TriageBucket.LIGHT_CHAT, Set.of(), "support_chat"),
                null))
                .isEqualTo(ExecutionEnvelope.writeCatalogWithGate());
    }
}
