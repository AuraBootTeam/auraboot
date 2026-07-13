package com.auraboot.framework.faq;

/**
 * One question/answer pair an LLM distilled out of a conversation, before any human
 * review. {@code confidence} is the model's own 0-1 estimate that this is a reusable
 * FAQ; it is advisory and never gates publication — a human always approves.
 */
public record ExtractedFaq(String question, String answer, double confidence) {
}
