package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executor for LLM_CALL action type (P1 — workflow LLM node).
 *
 * <p>Interpolates {@code ${var}} placeholders in {@code systemPrompt} and
 * {@code userPromptTemplate} against the execution context, calls the resolved
 * LLM provider via {@link LlmProviderFactory}, and stores the response text
 * under {@code context.<outputVariableName>} (default {@code llmOutput}) so
 * downstream automation steps can consume it.
 *
 * <p>Capability gating: when {@code thinkingEnabled=true} but the chosen model
 * does not support Anthropic Extended Thinking, this executor throws a
 * {@link BusinessException} (no silent drop). Aligns with the no-fallback red
 * line — we never quietly ignore a configured capability.
 *
 * <p>Failure semantics: any provider/HTTP/config error throws — the surrounding
 * {@code AutomationTriggerServiceImpl.executeAction} converts that into
 * {@code ActionResult.status=failed} without aborting the workflow unless
 * {@code action.continueOnError != true}, matching every other action node.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallExecutor implements ActionExecutor {

    /** Action type code matched against {@link AutomationAction#getType()}. */
    public static final String ACTION_TYPE = "llm_call";

    /** Default max_tokens when caller leaves it unset. Mirrors web-admin defaultConfig. */
    private static final int DEFAULT_MAX_TOKENS = 1024;

    /** Default Extended Thinking budget when {@code thinkingEnabled=true} and no value provided. */
    private static final int DEFAULT_THINKING_BUDGET_TOKENS = 8_000;

    /** Default context key the response text is stored under. */
    private static final String DEFAULT_OUTPUT_VARIABLE = "llmOutput";

    /**
     * Whitelist of image MIME types accepted by Anthropic vision. Mirrors the
     * provider documentation — sending other types (image/bmp, image/svg+xml,
     * etc.) would HTTP-400 from Anthropic. We reject upfront so workflow
     * authors get a clear error instead of an opaque upstream failure.
     */
    private static final Set<String> ALLOWED_IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    /**
     * Strict data-URI parser. We chose the data-URI shape (single string
     * carries both mediaType + base64 payload) over a parallel
     * {@code imageMimeTypeVariableNames} config because:
     *   1. Self-describing — one context variable, no risk of mediaType
     *      drift relative to the bytes.
     *   2. Matches what the AuraBot chat upload pipeline already produces.
     *   3. Frontend designer only needs ONE chip-list field.
     * Format: {@code data:image/<png|jpeg|gif|webp>;base64,<...>}.
     */
    private static final Pattern DATA_URI_PATTERN = Pattern.compile(
            "^data:(image/(?:png|jpeg|gif|webp));base64,(.+)$");

    /**
     * Provider codes that accept vision input. All other providers will be
     * rejected at executor level with an explicit error so workflow authors
     * see "this provider does not support vision" rather than a generic
     * provider-side HTTP 400 / IllegalArgumentException downstream.
     *
     * <p>Anthropic supports vision on Claude 3.5+/4.x/5.x. The OpenAI-compat
     * fall-through path used by DeepSeek/Qwen/Zhipu/etc. explicitly rejects
     * image content (see {@code OpenAiCompatibleLlmProvider#chat}), so we
     * pre-filter here to give a workflow-level error message.
     */
    private static final Set<String> VISION_CAPABLE_PROVIDERS = Set.of("anthropic");

    /**
     * Anthropic models that accept the {@code thinking} request field. Mirrors
     * {@code AnthropicLlmProvider.THINKING_CAPABLE_PATTERNS} but is duplicated
     * here so we can capability-gate <i>before</i> dispatching to the provider
     * (the provider silently drops thinking on legacy models, which is the
     * wrong contract for an explicitly-toggled workflow node — users want a
     * clear error rather than a silent no-op).
     */
    private static final List<String> THINKING_CAPABLE_MODEL_PATTERNS = List.of(
            "sonnet-4-6", "sonnet-4-7", "opus-4", "haiku-4");

    private final LlmProviderFactory llmProviderFactory;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("LLM_CALL action requires config");
        }

        String userPromptTemplate = (String) config.get("userPromptTemplate");
        if (userPromptTemplate == null || userPromptTemplate.isBlank()) {
            throw new IllegalArgumentException("LLM_CALL action requires userPromptTemplate");
        }

        String model = stringOr(config, "model", null);
        String systemPromptTemplate = (String) config.get("systemPrompt");
        int maxTokens = intOr(config, "maxTokens", DEFAULT_MAX_TOKENS);
        boolean thinkingEnabled = boolOr(config, "thinkingEnabled", false);
        int thinkingBudget = intOr(config, "thinkingBudgetTokens", DEFAULT_THINKING_BUDGET_TOKENS);
        String outputVariable = stringOr(config, "outputVariableName", DEFAULT_OUTPUT_VARIABLE);

        // Capability gate — fail loudly if user opted in to thinking on a model
        // that cannot honour it. Matches the "no silent fallback" rule from
        // P0-2 / Vision: a configured capability that the runtime ignores is a
        // bug surface, not a feature.
        if (thinkingEnabled && !modelSupportsThinking(model)) {
            throw new BusinessException(String.format(
                    "LLM_CALL: model '%s' does not support Extended Thinking. "
                            + "Disable thinkingEnabled or pick a thinking-capable model "
                            + "(claude-sonnet-4-6, claude-opus-4, claude-haiku-4).",
                    model == null ? "<unset>" : model));
        }

        // Interpolate ${var} placeholders against the workflow context. Same
        // simple substitution other action executors use (CallApiExecutor,
        // SendWebhookExecutor) — kept inline rather than promoting to a util
        // until the third or fourth executor needs it.
        // E.2 — Vision: read configured image-variable names. Empty/null means
        // the existing text-only path runs unchanged (regression safe). We
        // intentionally read this BEFORE provider resolution so a misconfigured
        // image var fails fast even on tenants without a vision-capable
        // provider configured — config errors should not depend on credentials.
        List<String> imageVariableNames = readStringList(config, "imageVariableNames");

        // Image variables are emitted as IMAGE content blocks, not text — so
        // when interpolating ${var} placeholders we must skip those keys to
        // avoid dumping the base64 payload into the user prompt body. Other
        // context entries interpolate normally.
        Set<String> imageVarSet = imageVariableNames.isEmpty()
                ? Set.of() : Set.copyOf(imageVariableNames);
        String userPrompt = processTemplate(userPromptTemplate, context, imageVarSet);
        String systemPrompt = systemPromptTemplate == null
                ? null : processTemplate(systemPromptTemplate, context, imageVarSet);

        // Resolve provider from CloudConfig for the current tenant. Falls back
        // to automation row tenantId if no MetaContext is in scope (e.g.
        // scheduler-driven runs) — same idiom as AutomationTriggerServiceImpl.
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        String providerCode = llmProviderFactory.resolveProviderByModel(model);
        LlmProviderFactory.ProviderConfig providerConfig =
                llmProviderFactory.resolveConfig(tenantId, providerCode);
        if (providerConfig == null) {
            throw new BusinessException(
                    "LLM_CALL: no LLM provider configured for tenant " + tenantId
                            + " (model=" + model + ", providerCode=" + providerCode + "). "
                            + "Configure an AI provider in Settings.");
        }

        String resolvedModel = (model != null && !model.isBlank())
                ? model : providerConfig.getDefaultModel();

        // E.2 — Vision provider gate. If image vars are configured, refuse
        // outright on non-vision providers so the workflow author sees a
        // clear "Provider X does not support vision" message at the executor
        // boundary, instead of a confusing IllegalArgumentException raised
        // deep inside OpenAiCompatibleLlmProvider#chat. Matches the explicit-
        // refusal pattern used in B.1 (no silent drop of capabilities).
        if (!imageVariableNames.isEmpty()
                && !VISION_CAPABLE_PROVIDERS.contains(providerConfig.getProviderCode())) {
            throw new IllegalArgumentException(
                    "Provider " + providerConfig.getProviderCode()
                            + " does not support vision. Configure an Anthropic-backed "
                            + "model (Claude 3.5+ / 4.x) or remove imageVariableNames.");
        }

        // Build the user-message content. With no image vars we keep the
        // legacy String content path so existing wire shape stays byte
        // identical (regression-tested by LlmCallExecutorTest). With one or
        // more image vars we switch to the multimodal block list: images
        // first, then text — Anthropic recommends anchoring the prompt to
        // the image so the model attends to visual content before reasoning.
        LlmChatRequest.Message userMessage;
        if (imageVariableNames.isEmpty()) {
            userMessage = LlmChatRequest.Message.builder()
                    .role("user")
                    .content(userPrompt)
                    .build();
        } else {
            userMessage = buildMultimodalUserMessage(imageVariableNames, context, userPrompt);
        }

        LlmChatRequest request = LlmChatRequest.builder()
                .model(resolvedModel)
                .providerCode(providerConfig.getProviderCode())
                .systemPrompt(systemPrompt)
                .messages(List.of(userMessage))
                .maxTokens(maxTokens)
                .thinking(thinkingEnabled
                        ? LlmChatRequest.ThinkingConfig.builder()
                                .enabled(true)
                                .budgetTokens(thinkingBudget)
                                .build()
                        : null)
                .build();

        LlmProvider provider = llmProviderFactory.getProvider(providerConfig.getProviderCode());
        if (provider == null) {
            throw new BusinessException("LLM_CALL: provider implementation not found for code "
                    + providerConfig.getProviderCode());
        }

        LlmChatResponse response;
        try {
            log.info("LLM_CALL invoking provider={} model={} maxTokens={} thinking={}",
                    providerConfig.getProviderCode(), resolvedModel, maxTokens, thinkingEnabled);
            response = provider.chat(request, providerConfig.getApiKey(), providerConfig.getBaseUrl());
        } catch (Exception e) {
            // CATCH: non-transactional LLM HTTP call failure. Re-wrap as
            // BusinessException so the trigger service records FAILED status
            // with a meaningful message, instead of letting the raw checked
            // exception bubble (matches AgentReplyTask error handling).
            // Note: BusinessException(message, cause) discards `message` and
            // keeps only the cause — so we build a single explicit string.
            String causeMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BusinessException("LLM_CALL failed: " + causeMsg);
        }

        String text = extractTextContent(response);
        if (text == null) {
            text = "";
        }

        // Persist into the running automation context so downstream actions
        // can reference ${<outputVariable>}. Same pattern as
        // AutomationTriggerServiceImpl puts action_<seq>_result into context,
        // but with a user-named key for ergonomics.
        context.put(outputVariable, text);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("model", resolvedModel);
        result.put("providerCode", providerConfig.getProviderCode());
        result.put("output", text);
        result.put("outputVariable", outputVariable);
        result.put("inputTokens", response.getInputTokens());
        result.put("outputTokens", response.getOutputTokens());
        result.put("stopReason", response.getStopReason());
        if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
            result.put("warnings", response.getWarnings());
        }
        return result;
    }

    @Override
    public boolean supports(String actionType) {
        return ACTION_TYPE.equals(actionType);
    }

    /**
     * Capability gate identical in spirit to
     * {@code AnthropicLlmProvider.supportsThinking}, kept here so we can fail
     * fast at action validation time rather than relying on the provider's
     * silent-drop behaviour.
     */
    private boolean modelSupportsThinking(String model) {
        if (model == null || model.isBlank()) return false;
        for (String pattern : THINKING_CAPABLE_MODEL_PATTERNS) {
            if (model.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Extract the first {@code text}-type content block. Matches
     * {@code AgentReplyTask.extractTextContent} semantics — workflow LLM nodes
     * are single-shot prompt/response, no tool calls.
     */
    private String extractTextContent(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText();
            }
        }
        return null;
    }

    /**
     * Inline {@code ${var}} substitution, identical to other automation
     * executors. Missing keys are left untouched so users can debug template
     * errors visually instead of silently producing empty strings.
     */
    private String processTemplate(String template, Map<String, Object> context) {
        return processTemplate(template, context, Set.of());
    }

    /**
     * Same as {@link #processTemplate(String, Map)} but skips a set of keys —
     * used by E.2 vision so {@code ${screenshot}} in the prompt body is NOT
     * replaced with the raw base64 payload (those keys flow as image blocks
     * instead). Skipped placeholders are LEFT IN PLACE intentionally so a
     * misconfigured workflow author sees the literal {@code ${screenshot}}
     * in their prompt and realises they shouldn't reference image vars in
     * text — silently stripping would hide the misconfiguration.
     */
    private String processTemplate(String template, Map<String, Object> context, Set<String> skipKeys) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (skipKeys.contains(entry.getKey())) continue;
            String placeholder = "${" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder,
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        return result;
    }

    private static String stringOr(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        if (value == null) return fallback;
        String s = value.toString();
        return s.isBlank() ? fallback : s;
    }

    private static int intOr(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    /**
     * Read {@code imageVariableNames} from config as a non-null list of
     * non-blank Strings. Tolerates the field being absent (returns empty
     * list) but rejects mistyped values (e.g. a single string, or a list
     * with non-string entries) with IllegalArgumentException so config
     * errors surface at executor entry rather than masquerading as
     * "image var missing in context" later.
     */
    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "LLM_CALL: " + key + " must be a List<String>, got " + raw.getClass().getSimpleName());
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(
                        "LLM_CALL: " + key + " entries must be non-blank strings, got " + o);
            }
            out.add(s.trim());
        }
        return out;
    }

    /**
     * Resolve each configured image variable from the workflow context and
     * fold them into a single multimodal user message. Image blocks are
     * emitted in the configured order; the text prompt comes last (matches
     * Anthropic guidance — image first so the model anchors reasoning to
     * visual content).
     *
     * <p>Strict validation per E.2 spec — every failure throws so workflow
     * authors see exactly which variable misbehaved:
     *   - missing in context → IllegalArgumentException("not found in context")
     *   - non-String value → IllegalArgumentException("must be a String")
     *   - not data:image/...;base64,... shape → IllegalArgumentException
     *   - mediaType outside allowlist → IllegalArgumentException
     * NO silent drop. NO best-effort fallback to text-only. Keeping this
     * loud is the whole point of E.2: when a workflow author wires up an
     * image variable, they expect the model to see the image — silently
     * stripping it would be a correctness bug masquerading as resilience.
     */
    private LlmChatRequest.Message buildMultimodalUserMessage(
            List<String> imageVariableNames,
            Map<String, Object> context,
            String userPrompt) {
        List<LlmChatRequest.MessageContentBlock> blocks =
                new ArrayList<>(imageVariableNames.size() + 1);

        for (String varName : imageVariableNames) {
            if (!context.containsKey(varName)) {
                throw new IllegalArgumentException(
                        "LLM_CALL: image variable '" + varName + "' not found in trigger context. "
                                + "Available keys: " + context.keySet());
            }
            Object value = context.get(varName);
            if (!(value instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(
                        "LLM_CALL: image variable '" + varName + "' must be a non-blank String "
                                + "in data:image/<type>;base64,<data> form, got "
                                + (value == null ? "null" : value.getClass().getSimpleName()));
            }
            Matcher m = DATA_URI_PATTERN.matcher(s);
            if (!m.matches()) {
                throw new IllegalArgumentException(
                        "LLM_CALL: image variable '" + varName + "' is not a valid data URI. "
                                + "Expected format: data:image/{png|jpeg|gif|webp};base64,<base64-data>");
            }
            String mediaType = m.group(1);
            String base64Data = m.group(2);
            if (!ALLOWED_IMAGE_MIME_TYPES.contains(mediaType)) {
                throw new IllegalArgumentException(
                        "LLM_CALL: image variable '" + varName + "' has unsupported media type '"
                                + mediaType + "'. Allowed: " + ALLOWED_IMAGE_MIME_TYPES);
            }
            blocks.add(LlmChatRequest.MessageContentBlock.builder()
                    .type("image")
                    .source(LlmChatRequest.ImageSource.builder()
                            .type("base64")
                            .mediaType(mediaType)
                            .data(base64Data)
                            .build())
                    .build());
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            blocks.add(LlmChatRequest.MessageContentBlock.builder()
                    .type("text")
                    .text(userPrompt)
                    .build());
        }

        return LlmChatRequest.Message.builder()
                .role("user")
                .content(blocks)
                .build();
    }

    private static boolean boolOr(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
