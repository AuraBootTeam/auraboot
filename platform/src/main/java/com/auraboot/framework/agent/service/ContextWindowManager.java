package com.auraboot.framework.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the context window (token budget) for Agent LLM calls.
 *
 * Allocation priority (highest to lowest):
 * 1. System prompt (non-negotiable)
 * 2. Tool definitions (non-negotiable for current step)
 * 3. Most recent messages (last 2 turns minimum)
 * 4. Memory section
 * 5. Older messages (truncated from oldest)
 *
 * Token estimation uses a simple heuristic: ~4 chars per token (English/code mix).
 */
@Slf4j
@Service
public class ContextWindowManager {

    private static final double CHARS_PER_TOKEN = 4.0;
    private static final int MIN_RESPONSE_BUDGET = 2000;  // Reserve for LLM response
    private static final int MIN_RECENT_MESSAGES = 4;      // Keep at least last 2 turns (user+assistant)

    /**
     * Estimate token count for a text string.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Estimate token count for tool definitions.
     * Each tool contributes: name + description + schema structure.
     */
    public int estimateToolTokens(List<?> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        // Rough estimate: ~150 tokens per tool (name + description + schema)
        return tools.size() * 150;
    }

    /**
     * Trim memory section to fit within the allocated budget.
     *
     * @param memorySection the full memory text
     * @param maxTokens     max tokens allowed for memory
     * @return trimmed memory text, or null if budget is 0
     */
    public String trimMemoryToFit(String memorySection, int maxTokens) {
        if (memorySection == null || maxTokens <= 0) return null;
        int currentTokens = estimateTokens(memorySection);
        if (currentTokens <= maxTokens) return memorySection;

        // Truncate by character count
        int maxChars = (int) (maxTokens * CHARS_PER_TOKEN);
        if (maxChars >= memorySection.length()) return memorySection;

        // Try to truncate at a section boundary
        String truncated = memorySection.substring(0, maxChars);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > maxChars / 2) {
            truncated = truncated.substring(0, lastNewline);
        }
        return truncated + "\n[... memory truncated to fit context window]";
    }

    /**
     * Calculate the budget allocation for each component given a total context window.
     *
     * @param contextWindowSize  total tokens available (from provider config)
     * @param systemPromptTokens tokens used by system prompt
     * @param toolTokens         tokens used by tool definitions
     * @param messageTokens      tokens used by conversation messages
     * @return budget allocation map with keys: memory, trimMessages, available
     */
    public BudgetAllocation calculateBudget(int contextWindowSize, int systemPromptTokens,
                                             int toolTokens, int messageTokens) {
        int fixed = systemPromptTokens + toolTokens + MIN_RESPONSE_BUDGET;
        int remaining = contextWindowSize - fixed;

        if (remaining <= 0) {
            log.warn("Context window exhausted: window={}, fixed={} (system={}, tools={}, response={})",
                    contextWindowSize, fixed, systemPromptTokens, toolTokens, MIN_RESPONSE_BUDGET);
            return new BudgetAllocation(0, 0, 0);
        }

        // Allocate: 80% messages, 20% memory (if messages don't need it all)
        int memoryBudget = Math.min(remaining / 5, 2000);  // Cap at ~2000 tokens for memory
        int messageBudget = remaining - memoryBudget;

        // If messages exceed budget, sacrifice memory first
        if (messageTokens > messageBudget) {
            memoryBudget = Math.max(0, remaining - messageTokens);
            messageBudget = remaining - memoryBudget;
        }

        int available = remaining - messageTokens - memoryBudget;
        if (available < 0) available = 0;

        return new BudgetAllocation(messageBudget, memoryBudget, available);
    }

    /**
     * Determine how many messages to keep from the conversation history,
     * prioritizing recent messages.
     *
     * @param messageTokenEstimates list of token counts per message (oldest first)
     * @param maxTokens             total token budget for messages
     * @return number of messages to keep from the END of the list
     */
    public int messagesToKeep(List<Integer> messageTokenEstimates, int maxTokens) {
        if (messageTokenEstimates.isEmpty()) return 0;

        // Always keep at least MIN_RECENT_MESSAGES from the end
        int keep = Math.min(MIN_RECENT_MESSAGES, messageTokenEstimates.size());
        int tokens = 0;

        // Count tokens for minimum messages (from end)
        for (int i = messageTokenEstimates.size() - keep; i < messageTokenEstimates.size(); i++) {
            tokens += messageTokenEstimates.get(i);
        }

        // Add older messages while budget allows
        for (int i = messageTokenEstimates.size() - keep - 1; i >= 0; i--) {
            int nextTokens = messageTokenEstimates.get(i);
            if (tokens + nextTokens > maxTokens) break;
            tokens += nextTokens;
            keep++;
        }

        return keep;
    }

    public record BudgetAllocation(int messageBudget, int memoryBudget, int availableTokens) {}
}
