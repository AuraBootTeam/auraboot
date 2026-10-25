package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuraBotChatService#buildRetrievalQuery} — history-aware retrieval query.
 *
 * <p>The gap it closes: a bare follow-up ("它防水吗?") retrieved on its own recalls the wrong chunks
 * because the entity lives in a prior turn (verified live: same fact scored 0.50 as a pronoun vs 0.85
 * with the entity named). The builder prepends recent USER turns so coreference resolves at the
 * retrieval plane, without disturbing first-turn / single-turn queries.
 */
class AuraBotChatServiceRetrievalQueryTest {

    @Test
    @DisplayName("no history → the message is used verbatim (first / single turn unchanged)")
    void noHistory() {
        assertEquals("运动手表 X2 Ultra 防水吗？",
                AuraBotChatService.buildRetrievalQuery(null, "运动手表 X2 Ultra 防水吗？"));
        assertEquals("运动手表 X2 Ultra 防水吗？",
                AuraBotChatService.buildRetrievalQuery(List.of(), "运动手表 X2 Ultra 防水吗？"));
    }

    @Test
    @DisplayName("bare-pronoun follow-up carries the entity from the prior user turn")
    void pronounFollowUpCarriesEntity() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("运动手表 X2 Ultra 有什么亮点？"),
                ChatMessage.assistant("蓝宝石镜面、双频 GPS、100 米防水、离线地图。"));
        String q = AuraBotChatService.buildRetrievalQuery(history, "它防水吗？");
        assertTrue(q.contains("X2 Ultra"), "prior entity must ride along: " + q);
        assertTrue(q.contains("它防水吗？"), "current message must stay: " + q);
        assertTrue(q.indexOf("X2 Ultra") < q.indexOf("它防水吗？"), "entity precedes current msg: " + q);
    }

    @Test
    @DisplayName("chained pronouns still reach the entity a few user turns back")
    void chainedPronouns() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("运动手表 X2 Ultra 有什么亮点？"),
                ChatMessage.assistant("……"),
                ChatMessage.user("它防水吗？"),
                ChatMessage.assistant("100 米。"));
        // third-turn pronoun; entity was named two user-turns ago
        String q = AuraBotChatService.buildRetrievalQuery(history, "那它续航呢？");
        assertTrue(q.contains("X2 Ultra"), "entity from 2 user-turns back must be included: " + q);
    }

    @Test
    @DisplayName("only USER turns are prepended (assistant/system chatter is not retrieval context)")
    void onlyUserTurns() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("You are a support bot."),
                ChatMessage.user("运动手表 X2 Ultra 有什么亮点？"),
                ChatMessage.assistant("这是一段很长的助手回答，不应进入检索查询作为噪声。"));
        String q = AuraBotChatService.buildRetrievalQuery(history, "它防水吗？");
        assertFalse(q.contains("You are a support bot"), "system prompt must not leak into query: " + q);
        assertFalse(q.contains("很长的助手回答"), "assistant answer must not become retrieval noise: " + q);
        assertTrue(q.contains("X2 Ultra"));
    }

    @Test
    @DisplayName("a current message duplicated in history is not counted twice")
    void currentDuplicatedInHistory() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("运动手表 X2 Ultra 有什么亮点？"),
                ChatMessage.user("它防水吗？")); // caller included the current message
        String q = AuraBotChatService.buildRetrievalQuery(history, "它防水吗？");
        // "它防水吗？" appears once (as the trailing current message), not twice
        assertEquals(q.lastIndexOf("它防水吗？"), q.indexOf("它防水吗？"), "current msg duplicated: " + q);
        assertTrue(q.contains("X2 Ultra"));
    }

    @Test
    @DisplayName("an over-long prior turn is capped so it augments without drowning the question")
    void longPriorCapped() {
        String longTurn = "运动手表 X2 Ultra " + "详情".repeat(300);
        String q = AuraBotChatService.buildRetrievalQuery(
                List.of(ChatMessage.user(longTurn)), "它防水吗？");
        assertTrue(q.length() < longTurn.length(), "over-long prior turn must be capped: len=" + q.length());
        assertTrue(q.contains("X2 Ultra"));
        assertTrue(q.endsWith("它防水吗？"));
    }
}
