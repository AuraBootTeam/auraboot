package com.auraboot.framework.agent.triage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the rule-based triage default. No Spring context needed.
 *
 * <p>2026-07-19: history-hotness rule deleted (review G5 — its input was
 * hardwired to 0, the rule never fired); explain-prefix short-circuit added
 * (review G4); light profiles configurable (review G6).
 */
@DisplayName("DefaultPreGroundingTriage — priority rules")
class DefaultPreGroundingTriageTest {

    private final DefaultPreGroundingTriage triage = new DefaultPreGroundingTriage();

    private TriageRequest req(String channel, String profileId, String message,
                              boolean pageCtx, boolean recordCtx) {
        return new TriageRequest(100L, 1L, channel, profileId, message, pageCtx, recordCtx);
    }

    @Test
    @DisplayName("Rule 1: webhook channel always forces ACP_RUN regardless of message")
    void rule1_webhookChannelForcesAcp() {
        TriageVerdict v = triage.triage(req("webhook", null, "hello", false, false));
        assertThat(v.bucket()).isEqualTo(TriageBucket.ACP_RUN);
        assertThat(v.reasonCodes()).contains("rule:channel_force_acp");
    }

    @Test
    @DisplayName("Rule 2: support_chat profile defaults to LIGHT_CHAT")
    void rule2_supportProfileLightChat() {
        TriageVerdict v = triage.triage(req("web", "support_chat", "今天天气如何", false, false));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.reasonCodes()).contains("rule:profile_light_default");
    }

    @Test
    @DisplayName("Rule 2 (G6): light-profile set is configurable, not hardcoded")
    void rule2_configurableProfiles() {
        DefaultPreGroundingTriage custom = new DefaultPreGroundingTriage(List.of("support_chat", "Kiosk_Mode"));
        TriageVerdict v = custom.triage(req("web", "kiosk_mode", "批量删除这些", false, false));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.reasonCodes()).contains("rule:profile_light_default");
        // The default instance does NOT know kiosk_mode.
        assertThat(triage.triage(req("web", "kiosk_mode", "批量删除这些", false, false)).bucket())
                .isEqualTo(TriageBucket.ACP_RUN);
    }

    // =====================================================================
    // Rule 3 — explain-prefix short-circuit (review G4)
    // =====================================================================

    @Test
    @DisplayName("G4: '为什么导出会失败' opens with 为什么 -> explanation, NOT durable export")
    void explainPrefix_beatsDurableKeyword() {
        TriageVerdict noCtx = triage.triage(req("web", null, "为什么导出会失败", false, false));
        assertThat(noCtx.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(noCtx.reasonCodes()).contains("rule:explain_prefix_short_circuit");

        TriageVerdict withCtx = triage.triage(req("web", null, "为什么导出会失败", true, false));
        assertThat(withCtx.bucket()).isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
        assertThat(withCtx.allowedReadOnlyTools()).isNotEmpty();
    }

    @Test
    @DisplayName("G4: capability/how-to questions about durable or action verbs stay explanatory")
    void explainPrefix_capabilityAndHowToQuestions() {
        for (String msg : new String[] {"是否支持批量导出", "如何做批量同步", "怎么创建客户", "how to export records", "why did the sync fail"}) {
            TriageVerdict v = triage.triage(req("web", null, msg, false, false));
            assertThat(v.bucket())
                    .as("message: %s", msg)
                    .isEqualTo(TriageBucket.LIGHT_CHAT);
            assertThat(v.reasonCodes())
                    .as("message: %s", msg)
                    .contains("rule:explain_prefix_short_circuit");
        }
    }

    @Test
    @DisplayName("G4 precision guard: politeness-imperatives and mid-sentence verbs do NOT short-circuit")
    void explainPrefix_doesNotCatchImperatives() {
        // "能不能/帮我" style requests are executions, not questions.
        assertThat(triage.triage(req("web", null, "能不能帮我删除这些记录", false, false)).bucket())
                .isEqualTo(TriageBucket.ACP_RUN);
        // Plain commands still route as before.
        assertThat(triage.triage(req("web", null, "批量删除客户", false, false)).bucket())
                .isEqualTo(TriageBucket.ACP_RUN);
        assertThat(triage.triage(req("web", null, "创建一个客户", false, false)).bucket())
                .isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(triage.triage(req("web", null, "创建一个客户", false, false)).reasonCodes())
                .contains("rule:keyword_platform_action_sync");
    }

    // =====================================================================
    // Rule 4 — keyword match
    // =====================================================================

    @Test
    @DisplayName("Rule 4: simple write intent stays in chat runtime for late tool-policy binding")
    void rule4_simpleWriteIntentStaysInChatTurn() {
        for (String msg : new String[] {"创建客户", "新增跟进任务", "更新订单", "create customer"}) {
            TriageVerdict v = triage.triage(req("web", null, msg, true, false));
            assertThat(v.bucket())
                    .as("message: %s", msg)
                    .isEqualTo(TriageBucket.LIGHT_CHAT);
            assertThat(v.reasonCodes()).contains("rule:keyword_platform_action_sync");
        }
    }

    @Test
    @DisplayName("Rule 4: explicitly durable platform work still enters ACP_RUN")
    void rule4_durablePlatformIntentAcpRun() {
        for (String msg : new String[] {"批量删除客户", "导出客户", "sync customers to external system", "bulk update records"}) {
            TriageVerdict v = triage.triage(req("web", null, msg, true, false));
            assertThat(v.bucket())
                    .as("message: %s", msg)
                    .isEqualTo(TriageBucket.ACP_RUN);
            assertThat(v.reasonCodes()).contains("rule:keyword_platform_durable");
        }
    }

    @Test
    @DisplayName("Rule 4: read-only platform questions stay in contextual answer with readonly tools")
    void rule4_readOnlyPlatformQuestionUsesContextualAnswer() {
        for (String msg : new String[] {"统计客户信息", "查询客户列表", "count customers", "list customers"}) {
            TriageVerdict v = triage.triage(req("web", null, msg, false, false));
            assertThat(v.bucket())
                    .as("message: %s", msg)
                    .isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
            assertThat(v.allowedReadOnlyTools())
                    .as("message: %s", msg)
                    .contains("schema.lookup", "record.view");
            assertThat(v.reasonCodes()).contains("rule:keyword_readonly_platform");
        }
    }

    @Test
    @DisplayName("Rule 4: mid-sentence explain verb + page context → CONTEXTUAL_ANSWER with readonly tools")
    void rule4_explainVerbWithContext() {
        TriageVerdict v = triage.triage(req("web", null, "这个表单是什么意思", true, false));
        assertThat(v.bucket()).isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
        assertThat(v.allowedReadOnlyTools()).contains("schema.lookup", "record.view");
    }

    @Test
    @DisplayName("Rule 4: mid-sentence explain verb without context → LIGHT_CHAT")
    void rule4_explainVerbNoContext() {
        TriageVerdict v = triage.triage(req("web", null, "你能解释一下机器学习吗", false, false));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.reasonCodes()).contains("rule:keyword_explain", "explain:no_context");
    }

    // =====================================================================
    // Default fallback
    // =====================================================================

    @Test
    @DisplayName("Default: trivial message no context → LIGHT_CHAT (low confidence)")
    void default_lightChat() {
        TriageVerdict v = triage.triage(req("web", null, "嗯嗯", false, false));
        assertThat(v.bucket()).isEqualTo(TriageBucket.LIGHT_CHAT);
        assertThat(v.confidence()).isEqualTo(0.50);
        assertThat(v.reasonCodes()).contains("rule:default_no_context");
    }

    @Test
    @DisplayName("Default: trivial message with context → CONTEXTUAL_ANSWER (low confidence)")
    void default_contextual() {
        TriageVerdict v = triage.triage(req("web", null, "嗯嗯", true, true));
        assertThat(v.bucket()).isEqualTo(TriageBucket.CONTEXTUAL_ANSWER);
        assertThat(v.allowedReadOnlyTools()).isNotEmpty();
    }

    @Test
    @DisplayName("Idempotency: same input yields same verdict")
    void idempotent() {
        TriageRequest r = req("web", null, "查询所有客户", false, false);
        TriageVerdict a = triage.triage(r);
        TriageVerdict b = triage.triage(r);
        assertThat(b).isEqualTo(a);
    }
}
