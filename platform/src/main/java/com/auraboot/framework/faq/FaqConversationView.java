package com.auraboot.framework.faq;

import java.time.Instant;
import java.util.List;

/**
 * Read models for the FAQ side of a conversation. Deliberately separate from the IM module's own
 * DTOs: {@code framework/im} is owned by the embeddable-channel track and is being reworked, and
 * the FAQ console only ever reads. Both shapes are <b>pid-only</b> — no internal id, no tenant id,
 * per the public record dual-id contract.
 */
public final class FaqConversationView {

    private FaqConversationView() {
    }

    /**
     * A conversation as it appears in the "pick something to distil" queue.
     *
     * @param candidateCount how many FAQ candidates have already been distilled from it — the
     *                       reviewer's cue that a conversation has been mined already, and the
     *                       reason this endpoint exists rather than a plain conversation list
     */
    public record Item(String pid,
                       String name,
                       String type,
                       long messageCount,
                       Instant lastMessageAt,
                       long candidateCount) {
    }

    /** One turn of the transcript, as the reviewer reads it. */
    public record Message(long seq,
                          String sender,
                          String content,
                          Instant sentAt) {
    }

    public record Page(List<Item> records, long total, int pageNum, int pageSize) {
    }
}
