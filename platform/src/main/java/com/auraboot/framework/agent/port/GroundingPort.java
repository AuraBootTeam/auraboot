package com.auraboot.framework.agent.port;

import java.util.List;

/**
 * Port interface for D1 Grounding — lightweight intent + object resolution from user message.
 * Defined in core module, implemented in enterprise-ai module.
 * <p>
 * Allows AuraBotChatService to resolve user intent and target object before tool selection,
 * without a compile-time dependency on the enterprise-ai module.
 * <p>
 * When enterprise-ai module is loaded, the grounding implementation registers as a Spring bean.
 * When not loaded, AuraBotChatService skips grounding and uses the default chat path.
 */
public interface GroundingPort {

    /**
     * Resolve intent and target object from a user message.
     *
     * @param tenantId    current tenant ID
     * @param userMessage the raw user message text
     * @param pageModel   current page model code (nullable, provides context)
     * @param recordId    current record ID (nullable, provides context)
     * @return grounding result with intent, object, confidence, and candidate skills
     */
    GroundingResult ground(Long tenantId, String userMessage, String pageModel, String recordId);

    /**
     * Result of grounding a user message — captures intent, target object, and candidate skills.
     *
     * @param intent          resolved intent (e.g., "create", "query", "summarize", "navigate")
     * @param object          resolved model code (e.g., "crm_lead"), or null if not object-specific
     * @param confidence      confidence score between 0.0 and 1.0
     * @param candidateSkills list of candidate skill codes that match the intent + object
     * @param readOnly        true if the intent is read-only (query, summarize, navigate)
     */
    record GroundingResult(
            String intent,
            String object,
            double confidence,
            List<String> candidateSkills,
            boolean readOnly
    ) {}
}
