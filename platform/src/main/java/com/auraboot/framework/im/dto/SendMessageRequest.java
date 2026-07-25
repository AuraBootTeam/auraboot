package com.auraboot.framework.im.dto;

import lombok.Data;

import java.util.List;

@Data
public class SendMessageRequest {
    private Long conversationId;
    private String messageType; // TEXT | IMAGE | FILE | CARD
    private String content;
    private String clientMsgId;
    private Object cardPayload; // Card Protocol JSON
    private Object attachments; // [{type, url, ...}]
    private Long replyToId;
    private List<String> mentions; // user ids or "ai" (legacy format)

    /**
     * Phase C.1 (chokepoint): triage verdict captured at runTurn entry.
     * Optional — when null the corresponding ab_im_message columns stay null,
     * matching pre-C.1 behavior. {@code bucket} must be lowercase
     * {@code light_chat | contextual_answer | acp_run} per the table CHECK.
     */
    private String triageBucket;
    private java.math.BigDecimal triageConfidence;
    /** JSON-encoded list of reason codes; passed through to the JSONB column. */
    private String triageReasonCodes;

    /**
     * Typed mention targets for polymorphic mentions (agent vs human).
     * If present, takes precedence over flat `mentions` list.
     */
    @Data
    public static class MentionTarget {
        private String type; // human | agent
        private Long id;     // user ID or agent definition ID
    }

    private List<MentionTarget> mentionTargets;

    /**
     * The mention tokens every consumer should use: the legacy {@code mentions} list
     * merged with {@code mentionTargets}, agents normalised to {@code agent:<id>}.
     *
     * <p>Three places consume mentions — message persistence (which is what
     * {@code InboxImListener} later reads back), the {@code ImMessageSentEvent} that
     * drives {@code GroupChatAgentRouter}, and the WebSocket handler. Each of them used
     * to read the raw {@code mentions} list, so a client populating the structured
     * {@code mentionTargets} field got its mentions silently dropped: no agent reply and
     * no Inbox item. Deriving all three from this one method keeps them from drifting.</p>
     */
    public List<String> effectiveMentions() {
        List<String> merged = new java.util.ArrayList<>();
        if (mentions != null) {
            merged.addAll(mentions);
        }
        if (mentionTargets != null) {
            for (MentionTarget target : mentionTargets) {
                if (target == null || target.getId() == null) {
                    continue;
                }
                String token = "agent".equalsIgnoreCase(target.getType())
                        ? "agent:" + target.getId()
                        : String.valueOf(target.getId());
                if (!merged.contains(token)) {
                    merged.add(token);
                }
            }
        }
        return merged;
    }
}
