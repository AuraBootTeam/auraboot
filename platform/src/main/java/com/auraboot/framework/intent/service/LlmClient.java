package com.auraboot.framework.intent.service;

/**
 * Abstraction for LLM chat calls.
 * Allows easy mocking in integration tests.
 */
public interface LlmClient {

    /**
     * Send a prompt to the LLM and return the text response.
     *
     * @param prompt the prompt text
     * @return the LLM response text
     */
    String chat(String prompt);
}
