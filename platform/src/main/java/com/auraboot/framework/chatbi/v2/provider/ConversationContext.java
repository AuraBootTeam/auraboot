package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.chatbi.v2.dto.SearchToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-turn conversation memory passed into {@link LlmProvider#translate}.
 *
 * <p>PRD 17 §7.4: keeps the last turn's metrics / dimensions / filters /
 * timeRange so the LLM can interpret follow-up phrases like "show last month
 * instead" or "drill down by region".
 *
 * <p>{@code messageHistory} is bounded at {@code multi-turn=5} per PRD §15
 * decision and serialized to {@code chatbi_conversation.messages_json} by W3.
 *
 * <p>Mutable POJO (not record) because W3's {@code ConversationService}
 * appends turn-by-turn.
 */
public class ConversationContext {

    private List<SearchToken> lastMetrics = new ArrayList<>();
    private List<SearchToken> lastDimensions = new ArrayList<>();
    private List<SearchToken> lastFilters = new ArrayList<>();
    private SearchToken lastTimeRange;
    private List<Message> messageHistory = new ArrayList<>();

    public ConversationContext() {
    }

    public List<SearchToken> getLastMetrics() {
        return lastMetrics;
    }

    public void setLastMetrics(List<SearchToken> lastMetrics) {
        this.lastMetrics = lastMetrics;
    }

    public List<SearchToken> getLastDimensions() {
        return lastDimensions;
    }

    public void setLastDimensions(List<SearchToken> lastDimensions) {
        this.lastDimensions = lastDimensions;
    }

    public List<SearchToken> getLastFilters() {
        return lastFilters;
    }

    public void setLastFilters(List<SearchToken> lastFilters) {
        this.lastFilters = lastFilters;
    }

    public SearchToken getLastTimeRange() {
        return lastTimeRange;
    }

    public void setLastTimeRange(SearchToken lastTimeRange) {
        this.lastTimeRange = lastTimeRange;
    }

    public List<Message> getMessageHistory() {
        return messageHistory;
    }

    public void setMessageHistory(List<Message> messageHistory) {
        this.messageHistory = messageHistory;
    }

    /** One turn in the rolling chat history. */
    public record Message(String role, String content) {
    }

    /** Convenience factory for an empty (single-turn) context. */
    public static ConversationContext empty() {
        return new ConversationContext();
    }
}
