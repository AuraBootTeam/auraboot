package com.auraboot.framework.faq;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/** Validates transcript bounds and redacts reusable FAQ candidates before review. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationFaqExtractionService {
    static final String TOOL_NAME = "submit_faqs";
    static final int MAX_TRANSCRIPT_CHARS = 24_000;
    @org.springframework.lang.Nullable
    private final FaqCandidateExtractor candidateExtractor;

    public void requireAvailable() {
        if (candidateExtractor == null) {
            throw new BusinessException(ResponseCode.UnsupportedFeature, "FAQ extraction is not installed");
        }
    }

    /**
     * @param transcript the rendered conversation, one turn per line
     * @return the pairs the model found, possibly empty — never null
     */
    public List<ExtractedFaq> extract(Long tenantId, String transcript) throws Exception {
        requireAvailable();
        if (transcript == null || transcript.isBlank()) {
            return List.of();
        }
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Conversation transcript is too long to distil (" + transcript.length()
                            + " chars, limit " + MAX_TRANSCRIPT_CHARS + "). Extract from a narrower message range.");
        }

        List<ExtractedFaq> faqs = new ArrayList<>();
        for (Map<String, Object> raw : candidateExtractor.extractCandidates(tenantId, transcript)) {
            String question = text(raw.get("question"));
            String answer = text(raw.get("answer"));
            // A pair missing either half is not a FAQ. Dropping it here rather than storing a
            // half-empty candidate keeps the review queue honest.
            if (question.isEmpty() || answer.isEmpty()) {
                log.warn("[faq-extract] tenant={} dropped an incomplete pair from the model: {}", tenantId, raw);
                continue;
            }
            // Redact structured PII before it becomes a candidate. The prompt already tells the model
            // not to carry personal data, but a published FAQ is read to everyone, and "the model
            // was told not to" is not the same as "it cannot" — a phone number the model copied
            // through would otherwise land in the knowledge base. This runs at the earliest point the
            // pair exists, so the reviewer sees an already-clean candidate.
            faqs.add(new ExtractedFaq(
                    PiiRedactor.redact(question), PiiRedactor.redact(answer), confidence(raw.get("confidence"))));
        }

        log.info("[faq-extract] tenant={} transcriptChars={} extracted={}", tenantId, transcript.length(), faqs.size());
        return faqs;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * Clamped to 0-1, and null when the model did not report one.
     *
     * <p>Not zero. Zero is a confidence — it says "this is worthless" — and a model that stayed
     * silent has said no such thing. A reviewer reading 0% would draw exactly the wrong conclusion.
     */
    private static Double confidence(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return Math.max(0d, Math.min(1d, number.doubleValue()));
    }

}
