package com.auraboot.framework.chatbi.v2.provider;

import java.util.List;

/**
 * Disambiguation prompt payload returned when the LLM cannot confidently
 * resolve a term ({@code confidence < 0.7} per PRD 17 §7.3) or when multiple
 * dictionary candidates tie. W3 will surface this to the UI via
 * {@code ChatBiAnswer.status = DISAMBIGUATION}.
 */
public record Disambiguation(
        String ambiguousTerm,
        List<Candidate> candidates) {

    /** A single resolution candidate the user can pick from. */
    public record Candidate(
            String type,    // METRIC / DIMENSION / VALUE / KEYWORD
            String code,
            String label,
            double score) {
    }
}
