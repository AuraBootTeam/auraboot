package com.auraboot.framework.aurabot.service;

import java.util.List;

/**
 * SPI interface for RAG context injection into AuraBot.
 * Implemented by RagRetrievalService in the shared AI runtime.
 * Optional — when no implementation is available, AuraBot works without RAG.
 */
public interface RagContextProvider {

    /**
     * Check if tenant has active knowledge bases with embedded content.
     */
    boolean hasActiveKnowledgeBases(Long tenantId);

    /**
     * Retrieve relevant context for a user query and format it for system prompt injection.
     *
     * @param tenantId  current tenant
     * @param query     user question
     * @param kbPids    specific KB PIDs (null = all active)
     * @return formatted Markdown context section, or empty string if no results
     */
    String retrieveContext(Long tenantId, String query, List<String> kbPids);
}
