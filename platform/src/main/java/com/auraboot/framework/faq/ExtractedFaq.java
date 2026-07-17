package com.auraboot.framework.faq;

/**
 * One question/answer pair an LLM distilled out of a conversation, before any human review.
 *
 * <p>{@code confidence} is the model's own 0-1 estimate that this is a reusable FAQ. It is advisory
 * and never gates publication — a human always approves.
 *
 * <p>It is <b>nullable on purpose</b>. A model that did not report a confidence has not told us it
 * is unsure, and collapsing that into 0.0 shows the reviewer "0%" — which reads as "the model has
 * no faith in this" when the truth is "the model said nothing". Absent and zero are different
 * claims, and only one of them is a claim.
 */
public record ExtractedFaq(String question, String answer, Double confidence) {
}
