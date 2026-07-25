package com.auraboot.framework.im.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mention contract that message persistence, the agent router and the inbox
 * listener all depend on.
 *
 * <p>Regression origin: a client that used the structured {@code mentionTargets} field
 * had its mentions silently dropped — the agent never replied to an @mention and no
 * MENTION inbox item was ever created — because every consumer read only the flat
 * {@code mentions} list.</p>
 */
@DisplayName("SendMessageRequest.effectiveMentions")
class SendMessageRequestMentionsTest {

    private static SendMessageRequest.MentionTarget target(String type, Long id) {
        SendMessageRequest.MentionTarget t = new SendMessageRequest.MentionTarget();
        t.setType(type);
        t.setId(id);
        return t;
    }

    @Test
    @DisplayName("agent targets become the agent:<id> token the router parses")
    void agentTargetsNormalised() {
        SendMessageRequest req = new SendMessageRequest();
        req.setMentionTargets(List.of(target("agent", 4L)));

        // GroupChatAgentRouter matches on exactly this shape; anything else never routes.
        assertEquals(List.of("agent:4"), req.effectiveMentions());
    }

    @Test
    @DisplayName("human targets keep their raw id so the inbox listener can parse them")
    void humanTargetsKeepRawId() {
        SendMessageRequest req = new SendMessageRequest();
        req.setMentionTargets(List.of(target("human", 8123L)));

        // InboxImListener does Long.parseLong on each token to find the mentioned user.
        assertEquals(List.of("8123"), req.effectiveMentions());
    }

    @Test
    @DisplayName("legacy mentions and structured targets merge without duplicates")
    void mergesBothShapes() {
        SendMessageRequest req = new SendMessageRequest();
        req.setMentions(List.of("agent:4", "999"));
        req.setMentionTargets(List.of(target("agent", 4L), target("human", 7L)));

        List<String> result = req.effectiveMentions();
        assertEquals(3, result.size(), "agent:4 appeared in both shapes and must not double: " + result);
        assertTrue(result.containsAll(List.of("agent:4", "999", "7")), "merged set wrong: " + result);
    }

    @Test
    @DisplayName("case-insensitive on target type")
    void caseInsensitiveType() {
        SendMessageRequest req = new SendMessageRequest();
        req.setMentionTargets(List.of(target("AGENT", 12L)));
        assertEquals(List.of("agent:12"), req.effectiveMentions());
    }

    @Test
    @DisplayName("null / id-less targets are skipped rather than producing agent:null")
    void skipsIncompleteTargets() {
        SendMessageRequest req = new SendMessageRequest();
        req.setMentionTargets(java.util.Arrays.asList(target("agent", null), null, target("agent", 5L)));
        assertEquals(List.of("agent:5"), req.effectiveMentions());
    }

    @Test
    @DisplayName("no mentions at all yields an empty list, never null")
    void emptyWhenNothingMentioned() {
        assertTrue(new SendMessageRequest().effectiveMentions().isEmpty());
    }
}
