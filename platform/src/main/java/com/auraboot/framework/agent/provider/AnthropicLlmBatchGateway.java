package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.BatchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Messages Batch API adapter for the provider-neutral batch gateway.
 */
@Component
@RequiredArgsConstructor
public class AnthropicLlmBatchGateway implements LlmBatchGateway {

    private final AnthropicBatchService batchService;
    private final AgentProperties agentProperties;

    @Override
    public String submit(List<Item> items, String purpose) {
        String model = agentProperties.getAnthropic().getDefaultModel();
        List<BatchRequest> requests = items.stream()
                .map(item -> BatchRequest.builder()
                        .customId(item.customId())
                        .params(AnthropicRequest.builder()
                                .model(model)
                                .max_tokens(item.maxOutputTokens())
                                .system(item.systemPrompt())
                                .messages(List.of(AnthropicRequest.Message.builder()
                                        .role("user")
                                        .content(item.userPrompt())
                                        .build()))
                                .build())
                        .build())
                .toList();
        return batchService.submitBatch(requests, purpose);
    }
}
