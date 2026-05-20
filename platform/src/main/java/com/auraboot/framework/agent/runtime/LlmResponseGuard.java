package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatResponse;

/**
 * Shared validation for provider-normalized LLM responses before runtime loops
 * read token counters, stop reasons, or content blocks.
 */
public final class LlmResponseGuard {

    private LlmResponseGuard() {
    }

    public static LlmChatResponse requireContent(LlmChatResponse response, String operation) {
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            throw new EmptyLlmResponseException(operation);
        }
        return response;
    }

    public static final class EmptyLlmResponseException extends IllegalStateException {
        public EmptyLlmResponseException(String operation) {
            super("Empty response from LLM" + operationSuffix(operation));
        }

        private static String operationSuffix(String operation) {
            if (operation == null || operation.isBlank()) {
                return "";
            }
            return " during " + operation;
        }
    }
}
