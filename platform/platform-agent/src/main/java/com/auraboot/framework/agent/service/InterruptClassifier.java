package com.auraboot.framework.agent.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Classify a user-interrupt message per ACP-Ideal §6.1.5.
 *
 * Decision surface:
 *   replace_intent   — user wants to STOP or change direction
 *   append_context   — user is adding info / clarifying, keep current run
 *   insert_subtask   — new intent is independent, parallel to current
 *
 * Tier 1: keyword matching (zh + en). Deterministic, 0ms, covers ~80% of
 *         real-world interrupts ("stop", "cancel", "等等", "改成", "另外").
 * Tier 2: LLM classifier (Haiku-level) when keywords are ambiguous.
 *         Optional — if no LLM provider is wired, we default to
 *         append_context (safest — keeps the current run, adds the new
 *         message to ephemeral context).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterruptClassifier {

    public static final String REPLACE_INTENT   = "replace_intent";
    public static final String APPEND_CONTEXT   = "append_context";
    public static final String INSERT_SUBTASK   = "insert_subtask";

    // ------------------------------------------------------------------ keywords

    /** Strong stop / redirect signals — message is almost certainly a replace_intent. */
    private static final Set<String> REPLACE_KEYWORDS_ZH = Set.of(
            "停", "取消", "等等", "不对", "换成", "别", "先停", "先别", "算了");
    private static final Set<String> REPLACE_KEYWORDS_EN = Set.of(
            "stop", "cancel", "nevermind", "never mind", "wait", "hold on", "scratch that", "abort",
            "change to", "instead of");

    /** Clarification / correction signals — append_context. */
    private static final Set<String> APPEND_KEYWORDS_ZH = Set.of(
            "另外补充", "顺便", "再加一个条件", "把条件改成", "参数应该是", "更正一下",
            "对了还", "再加");
    private static final Set<String> APPEND_KEYWORDS_EN = Set.of(
            "also add", "by the way", "also include", "correction", "the parameter should be",
            "one more constraint", "also consider");

    /** Independent-task signals — insert_subtask. */
    private static final Set<String> INSERT_KEYWORDS_ZH = Set.of("另外", "顺便帮我", "同时");
    private static final Set<String> INSERT_KEYWORDS_EN = Set.of(
            "by the way", "also", "in parallel", "separately");

    // ------------------------------------------------------------------ API

    /** Result of a classification pass. Never null. */
    @Data
    @Builder
    public static class Classification {
        private String subPolicy;
        private double confidence;
        private String tier;        // keyword | llm | default
        private String reason;
    }

    /**
     * Classify the new message in the context of the current run. Keyword
     * tier only for now — LLM fallback is a follow-up wiring.
     */
    public Classification classify(String newMessage, String currentIntentSummary) {
        if (newMessage == null || newMessage.isBlank()) {
            return Classification.builder()
                    .subPolicy(APPEND_CONTEXT)
                    .confidence(0.40)
                    .tier("default")
                    .reason("empty interrupt message")
                    .build();
        }
        String normalized = newMessage.toLowerCase().trim();

        // Priority: replace > insert > append. A message with both "stop" and
        // "also" is a replace; redirect wins over augmentation.
        if (containsAny(newMessage, REPLACE_KEYWORDS_ZH) || containsAny(normalized, REPLACE_KEYWORDS_EN)) {
            return Classification.builder()
                    .subPolicy(REPLACE_INTENT).confidence(0.90).tier("keyword")
                    .reason("stop/redirect keyword present").build();
        }

        // Insert: independent parallel task (different object + independent marker)
        boolean insertSignal = containsAny(newMessage, INSERT_KEYWORDS_ZH)
                || containsAny(normalized, INSERT_KEYWORDS_EN);
        boolean appendSignal = containsAny(newMessage, APPEND_KEYWORDS_ZH)
                || containsAny(normalized, APPEND_KEYWORDS_EN);

        if (insertSignal && !appendSignal) {
            return Classification.builder()
                    .subPolicy(INSERT_SUBTASK).confidence(0.75).tier("keyword")
                    .reason("independent-task marker without augmentation signal").build();
        }

        if (appendSignal) {
            return Classification.builder()
                    .subPolicy(APPEND_CONTEXT).confidence(0.80).tier("keyword")
                    .reason("augmentation/clarification marker").build();
        }

        // Tier 2 would go here — LLM call. For v0 we default to append_context
        // when nothing matches (safest: keeps current run intact, adds the
        // message to ephemeral layer).
        return Classification.builder()
                .subPolicy(APPEND_CONTEXT).confidence(0.50).tier("default")
                .reason("no strong keyword signal — safe default")
                .build();
    }

    private boolean containsAny(String text, Set<String> needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }
}
