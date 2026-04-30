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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        String userPrompt = processTemplate(userPromptTemplate, context);
        String systemPrompt = systemPromptTemplate == null
                ? null : processTemplate(systemPromptTemplate, context);

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

        LlmChatRequest request = LlmChatRequest.builder()
                .model(resolvedModel)
                .providerCode(providerConfig.getProviderCode())
                .systemPrompt(systemPrompt)
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user")
                        .content(userPrompt)
                        .build()))
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
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
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

    private static boolean boolOr(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
