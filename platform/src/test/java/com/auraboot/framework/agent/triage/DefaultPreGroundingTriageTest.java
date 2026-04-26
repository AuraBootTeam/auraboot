package com.auraboot.framework.agent.triage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the rule-based triage default. No Spring context needed:
 * the impl has no dependencies.
 */
@DisplayName("DefaultPreGroundingTriage — 5 priority rules")
class DefaultPreGroundingTriageTest {

    private final DefaultPreGroundingTriage triage = new DefaultPreGroundingTriage();

    @Test
    @DisplayName("Rule 1: webhook channel always forces ACP_RUN regardless of message")
    void rule1_webhookChannelForcesAcp() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "webhook", null, "hello", false, false, 0));
        assertThat(v.bucket()).isEqualTo(TriageBucket.ACP_RUN);
        assertThat(v.reasonCodes()).contains("rule:channel_force_acp");
    }

    @Test
    @DisplayName("Rule 2: support_chat profile defaults to LIGHT_CHAT")
    void rule2_supportProfileLightChat() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", "support_chat", "今天天气如何", false, false, 0));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.reasonCodes()).contains("rule:profile_light_default");
    }

    @Test
    @DisplayName("Rule 3: history streak of 5+ light turns continues LIGHT_CHAT for non-platform message")
    void rule3_historyStreakKeepsLight() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", null, "你好", false, false, 6));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.reasonCodes()).contains("rule:history_light_streak");
    }

    @Test
    @DisplayName("Rule 3 break: platform keyword overrides streak")
    void rule3_platformKeywordBreaksStreak() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", null, "查询本月销售", false, false, 6));
        assertThat(v.bucket()).isEqualTo(TriageBucket.ACP_RUN);
        assertThat(v.reasonCodes()).contains("rule:keyword_platform_verb");
    }

    @Test
    @DisplayName("Rule 4: CRUD verb in message → ACP_RUN")
    void rule4_crudVerbAcpRun() {
        for (String msg : new String[] {"创建客户", "delete record", "更新订单", "查询统计"}) {
            TriageVerdict v = triage.triage(new TriageRequest(
                    100L, 1L, "web", null, msg, true, false, 0));
            assertThat(v.bucket())
                    .as("message: %s", msg)
                    .isEqualTo(TriageBucket.ACP_RUN);
        }
    }

    @Test
    @DisplayName("Rule 4: explain verb + page context → CONTEXTUAL_ANSWER with readonly tools")
    void rule4_explainVerbWithContext() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", null, "这个表单是什么意思", true, false, 0));
        assertThat(v.bucket()).isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
        assertThat(v.allowedReadOnlyTools()).contains("schema.lookup", "record.view");
    }

    @Test
    @DisplayName("Rule 4: explain verb without context → LIGHT_CHAT")
    void rule4_explainVerbNoContext() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", null, "你能解释一下机器学习吗", false, false, 0));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.reasonCodes()).contains("rule:keyword_explain_no_context");
    }

    @Test
    @DisplayName("Rule 5 default: trivial message no context → LIGHT_CHAT (low confidence)")
    void rule5_defaultLightChat() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", null, "嗯嗯", false, false, 0));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.confidence()).isEqualTo(0.50);
        assertThat(v.reasonCodes()).contains("rule:default_no_context");
    }

    @Test
    @DisplayName("Rule 5 default: trivial message with context → CONTEXTUAL_ANSWER (low confidence)")
    void rule5_defaultContextual() {
        TriageVerdict v = triage.triage(new TriageRequest(
                100L, 1L, "web", null, "嗯嗯", true, true, 0));
        assertThat(v.bucket()).isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
        assertThat(v.allowedReadOnlyTools()).isNotEmpty();
    }

    @Test
    @DisplayName("Idempotency: same input yields same verdict")
    void idempotent() {
        TriageRequest req = new TriageRequest(
                100L, 1L, "web", null, "查询所有客户", false, false, 0);
        TriageVerdict a = triage.triage(req);
        TriageVerdict b = triage.triage(req);
        assertThat(b).isEqualTo(a);
    }
}
