package com.auraboot.framework.agent.integration.faq;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.faq.FaqCandidateExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;

/** Native tool protocol for FAQ extraction; core applies candidate policy and PII redaction. */
@Component
@Slf4j
@RequiredArgsConstructor
public class LlmFaqCandidateExtractor implements FaqCandidateExtractor {
    private static final String TOOL_NAME = "submit_faqs";
    private final LlmProviderFactory llmProviderFactory;
    private static final int MAX_TOKENS = 2048;


    /** Tool schema. An empty {@code faqs} array is valid and is the expected answer for most conversations. */
    static final Map<String, Object> FAQ_SCHEMA = objectSchema(
            new LinkedHashMap<>(Map.of(
                    "faqs", Map.of(
                            "type", "array",
                            "description", "Every reusable question/answer pair this conversation actually "
                                    + "contains. Return an EMPTY array when it contains none.",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", new LinkedHashMap<>(Map.of(
                                            "question", Map.of("type", "string",
                                                    "description", "The customer's question, rewritten to stand on its "
                                                            + "own without the surrounding conversation."),
                                            "answer", Map.of("type", "string",
                                                    "description", "The answer as actually given in this conversation. "
                                                            + "If nobody answered the question, omit the pair entirely."),
                                            "confidence", Map.of("type", "number",
                                                    "description", "0.0-1.0 confidence that this is a reusable FAQ."))),
                                    // confidence is required: asking for it and then accepting its
                                    // absence just means we invent a value for it downstream.
                                    "required", List.of("question", "answer", "confidence"),
                                    "additionalProperties", false)))),
            List.of("faqs"));

    private static final String SYSTEM_PROMPT = """
            You are a knowledge-base curator. Read the customer-service conversation and call \
            the tool with every reusable question/answer pair it contains.

            Extract ONLY what the conversation actually states. Never invent a question that \
            was not asked. Never invent, complete or improve an answer that the support agent \
            did not give. A question that was asked but never answered is NOT a FAQ — omit it.

            If the conversation contains no reusable FAQ at all — small talk, an unresolved \
            complaint, a question left hanging — call the tool with an empty faqs array. \
            Returning nothing is a correct and expected answer; inventing a plausible pair to \
            appear useful is a serious error, because these pairs are published to customers.

            These pairs are published to every future customer, so they must contain NO personal \
            data. Do not carry over the customer's name, account handle, order number, address, or \
            any other identifier — rewrite the question generically ("how do I reset my password?", \
            not "how does 张三 reset the password for account abc123?"). Phone numbers, emails, ID \
            numbers and card numbers are stripped automatically, but names and handles are yours to \
            leave out.
            """;

    @Override
    public List<Map<String, Object>> extractCandidates(Long tenantId, String transcript) throws Exception {
        LlmProviderFactory.ProviderResolution resolution = llmProviderFactory.resolveProvider(tenantId, null);
        if (resolution == null || resolution.getProvider() == null) {
            throw new BusinessException(ResponseCode.SystemError,
                    "No LLM provider is configured for this tenant — cannot distil FAQs");
        }
        LlmProvider provider = resolution.getProvider();
        LlmProviderFactory.ProviderConfig config = resolution.getConfig();

        LlmChatRequest.Tool tool = LlmChatRequest.Tool.builder()
                .name(TOOL_NAME)
                .description("Submit the reusable FAQ pairs found in a customer-service conversation")
                .inputSchema(FAQ_SCHEMA)
                .build();

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(SYSTEM_PROMPT)
                .messages(List.of(LlmChatRequest.Message.text("user", transcript)))
                .tools(List.of(tool))
                // required, not auto: "no FAQs here" must come back as an empty array, so that an
                // absent tool call is unambiguously a provider/model failure rather than a verdict.
                .toolChoice("required")
                .maxTokens(MAX_TOKENS)
                .build();

        LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
        Map<String, Object> args = firstToolInput(response, TOOL_NAME);
        if (args == null) {
            log.warn("[faq-extract] tenant={} model did not call {} — treating as no FAQs found",
                    tenantId, TOOL_NAME);
            return List.of();
        }

        log.info("[faq-extract] tenant={} transcriptChars={} (in={} out={} tokens)",
                tenantId, transcript.length(), response.getInputTokens(), response.getOutputTokens());
        return mapList(args.get("faqs"));
    }

    // ---- parsing helpers (mirror CsComplaintEmailExtractionLiveIT) ------------

    private static Map<String, Object> firstToolInput(LlmChatResponse resp, String toolName) {
        if (resp == null || resp.getContent() == null) {
            return null;
        }
        for (LlmChatResponse.ContentBlock block : resp.getContent()) {
            if ("tool_use".equals(block.getType()) && toolName.equals(block.getName())) {
                return block.getInput() != null ? block.getInput() : Map.of();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
