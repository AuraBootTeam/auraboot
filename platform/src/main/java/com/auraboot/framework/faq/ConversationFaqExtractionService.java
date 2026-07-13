package com.auraboot.framework.faq;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Distils reusable FAQ pairs out of a conversation transcript using the tenant's LLM
 * via native tool-calling.
 *
 * <p>The model is handed a single tool whose schema is the answer shape we want, and is
 * told to call it with every reusable pair the conversation actually contains. Asking for
 * a tool call rather than free text is what makes the output parseable without a fragile
 * prose parser: DeepSeek (and any OpenAI-compatible provider) reports tools natively —
 * {@code OpenAiCompatibleLlmProvider.supportsTools()} is true and it round-trips
 * {@code tool_calls} back into a {@code tool_use} content block.
 *
 * <p><b>Empty is a first-class answer.</b> Most conversations contain no reusable FAQ —
 * chit-chat, an unresolved complaint, a question nobody answered. The tool is invoked with
 * {@code toolChoice=required} and an explicitly nullable array, so "nothing here" is
 * expressed as an empty {@code faqs} array rather than as a missing tool call. A model that
 * invents a plausible-looking Q&amp;A here would poison a customer-facing knowledge base, so
 * the prompt forbids it and {@link ConversationFaqExtractionLiveIT} asserts it on negative
 * samples.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationFaqExtractionService {

    static final String TOOL_NAME = "submit_faqs";

    /**
     * Transcripts longer than this are refused rather than silently truncated: a truncated
     * transcript yields FAQs whose answers were cut off mid-sentence, and the caller would
     * never know. ~24k chars is comfortably inside deepseek-chat's context with room for
     * the tool schema and the response.
     */
    static final int MAX_TRANSCRIPT_CHARS = 24_000;

    private static final int MAX_TOKENS = 2048;

    private final LlmProviderFactory llmProviderFactory;

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
                                    "required", List.of("question", "answer"),
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
            """;

    /**
     * @param transcript the rendered conversation, one turn per line
     * @return the pairs the model found, possibly empty — never null
     */
    public List<ExtractedFaq> extract(Long tenantId, String transcript) throws Exception {
        if (transcript == null || transcript.isBlank()) {
            return List.of();
        }
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Conversation transcript is too long to distil (" + transcript.length()
                            + " chars, limit " + MAX_TRANSCRIPT_CHARS + "). Extract from a narrower message range.");
        }

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

        List<ExtractedFaq> faqs = new ArrayList<>();
        for (Map<String, Object> raw : mapList(args.get("faqs"))) {
            String question = text(raw.get("question"));
            String answer = text(raw.get("answer"));
            // A pair missing either half is not a FAQ. Dropping it here rather than storing a
            // half-empty candidate keeps the review queue honest.
            if (question.isEmpty() || answer.isEmpty()) {
                log.warn("[faq-extract] tenant={} dropped an incomplete pair from the model: {}", tenantId, raw);
                continue;
            }
            faqs.add(new ExtractedFaq(question, answer, confidence(raw.get("confidence"))));
        }

        log.info("[faq-extract] tenant={} transcriptChars={} extracted={} (in={} out={} tokens)",
                tenantId, transcript.length(), faqs.size(),
                response.getInputTokens(), response.getOutputTokens());
        return faqs;
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

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** Clamped to 0-1: the model occasionally reports a percentage or omits the field entirely. */
    private static double confidence(Object value) {
        if (!(value instanceof Number number)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, number.doubleValue()));
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
