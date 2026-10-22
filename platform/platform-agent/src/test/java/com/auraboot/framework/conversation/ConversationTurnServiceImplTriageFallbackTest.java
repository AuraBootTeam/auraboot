package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.agent.triage.TriageVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the channel-sensitive triage failure fallback (R2 review
 * §6-3). The load-bearing property: a classifier crash on a HUMAN channel
 * must degrade to a read-only contextual chat turn — not to ACP_RUN (the
 * heavier, more capable runtime; a user-visible Failed on deployments
 * without ACP wiring) and never to LIGHT_CHAT (full write catalog).
 */
@DisplayName("ConversationTurnServiceImpl.triageFailureFallback — channel-sensitive fail-closed")
class ConversationTurnServiceImplTriageFallbackTest {

    @Test
    @DisplayName("system channels (webhook/bpm/scheduled) keep failing closed to ACP_RUN")
    void systemChannels_stayDurable() {
        for (String channel : new String[] {"webhook", "bpm", "scheduled", "WEBHOOK"}) {
            TriageVerdict v = ConversationTurnServiceImpl.triageFailureFallback(channel);
            assertThat(v.bucket()).as("channel: %s", channel).isEqualTo(TriageBucket.ACP_RUN);
            assertThat(v.reasonCodes()).contains("triage_exception");
        }
    }

    @Test
    @DisplayName("human channels degrade to READ-ONLY contextual chat, never LIGHT_CHAT")
    void humanChannels_degradeToReadOnlyContextual() {
        for (String channel : new String[] {"web", "im", "im_group", "agent_reply", null}) {
            TriageVerdict v = ConversationTurnServiceImpl.triageFailureFallback(channel);
            assertThat(v.bucket()).as("channel: %s", channel).isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
            // Non-empty read-only grant is what arms the G10 envelope cap —
            // an empty set here would silently hand the degraded turn the
            // full write catalog.
            assertThat(v.allowedReadOnlyTools()).as("channel: %s", channel).isNotEmpty();
            assertThat(v.bucket()).isNotEqualTo(TriageBucket.LIGHT_CHAT);
            assertThat(v.reasonCodes()).contains("triage_exception_readonly_fallback");
        }
    }
}
