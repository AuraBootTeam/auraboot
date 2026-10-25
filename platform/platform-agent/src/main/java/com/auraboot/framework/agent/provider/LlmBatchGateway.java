package com.auraboot.framework.agent.provider;

import java.util.List;

/**
 * Provider-neutral asynchronous batch inference capability.
 *
 * <p>Business services submit prompts and output budgets; an adapter chooses
 * its configured provider/model and translates the wire format.
 */
public interface LlmBatchGateway {

    record Item(
            String customId,
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens) {
    }

    String submit(List<Item> items, String purpose);
}
