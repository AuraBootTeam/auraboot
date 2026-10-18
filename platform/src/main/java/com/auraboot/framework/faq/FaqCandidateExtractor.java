package com.auraboot.framework.faq;

import java.util.List;
import java.util.Map;

/** Untrusted model candidates; the FAQ service validates and redacts every result. */
public interface FaqCandidateExtractor {
    List<Map<String, Object>> extractCandidates(Long tenantId, String transcript) throws Exception;
}
