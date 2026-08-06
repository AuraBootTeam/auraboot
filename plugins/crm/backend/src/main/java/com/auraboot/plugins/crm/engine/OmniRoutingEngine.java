package com.auraboot.plugins.crm.engine;

/**
 * Pure omnichannel session routing (CRM gap #16).
 *
 * <p>Routes an inbound session to a queue and assigns a priority from its
 * channel and customer tier. Separated from the handler so the rules are
 * unit-testable without a Spring context or database.
 *
 * <p>Queue by channel (email/chat/phone/social → dedicated queue; unknown →
 * general). Priority: phone is synchronous so always {@code urgent}; otherwise
 * VIP customers get {@code high}, the rest {@code normal}.
 */
public final class OmniRoutingEngine {

    private OmniRoutingEngine() {
    }

    public record Result(String queue, String priority) {
    }

    public static Result route(String channel, boolean vip) {
        String ch = channel == null ? "" : channel.trim().toLowerCase();
        String queue = switch (ch) {
            case "email" -> "email_queue";
            case "chat" -> "chat_queue";
            case "phone" -> "phone_queue";
            case "social" -> "social_queue";
            default -> "general_queue";
        };
        String priority;
        if ("phone".equals(ch)) {
            priority = "urgent";
        } else if (vip) {
            priority = "high";
        } else {
            priority = "normal";
        }
        return new Result(queue, priority);
    }
}
