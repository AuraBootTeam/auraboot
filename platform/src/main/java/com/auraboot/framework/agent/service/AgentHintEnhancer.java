package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Batch service that uses LLM to generate high-quality agent descriptions for commands.
 * Generates 4 fields: agent_hint, precondition_description, side_effect_description, output_description.
 * After enhancement, triggers capability view sync for each updated command.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHintEnhancer {

    private final LlmProviderFactory llmProviderFactory;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final CapabilityViewService capabilityViewService;

    private static final int LLM_BATCH_SIZE = 10;

    /**
     * Enhance commands that have missing or short agent_hint values.
     *
     * @param tenantId  the tenant to process
     * @param batchSize max number of commands to process in this run
     * @return count of commands whose agent_hint was successfully updated
     */
    public int enhanceBatch(Long tenantId, int batchSize) {
        // 1. Resolve LLM provider
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
        if (config == null) {
            throw new IllegalStateException(
                    "No LLM provider configured. Please configure an LLM provider in Cloud Config.");
        }
        String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(null, config);
        LlmProvider provider = llmProviderFactory.getProvider(effectiveProviderCode);

        // 2. Query commands with missing or short agent_hint
        String sql = """
                SELECT id, pid, code, model_code, description, agent_hint,
                       execution_config, cmd_risk_level, input_schema, extension
                FROM ab_command_definition
                WHERE tenant_id = #{params.tenantId}
                  AND is_current = true
                  AND deleted_flag = FALSE
                  AND (agent_hint IS NULL OR LENGTH(TRIM(agent_hint)) < 30)
                ORDER BY model_code, code
                LIMIT #{params.limit}
                """;
        List<Map<String, Object>> commands = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "limit", batchSize));

        if (commands.isEmpty()) {
            log.info("No commands need agent_hint enhancement for tenant {}", tenantId);
            return 0;
        }

        log.info("Found {} commands needing agent_hint enhancement for tenant {}", commands.size(), tenantId);

        // 3. Process in LLM-sized batches
        int enhanced = 0;
        for (int i = 0; i < commands.size(); i += LLM_BATCH_SIZE) {
            List<Map<String, Object>> batch = commands.subList(i,
                    Math.min(i + LLM_BATCH_SIZE, commands.size()));
            enhanced += processBatch(tenantId, provider, config, batch);
            log.info("Progress: {}/{} commands processed", Math.min(i + LLM_BATCH_SIZE, commands.size()),
                    commands.size());
        }

        log.info("Enhanced {} out of {} commands for tenant {}", enhanced, commands.size(), tenantId);
        return enhanced;
    }

    @SuppressWarnings("unchecked")
    private int processBatch(Long tenantId, LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                             List<Map<String, Object>> commands) {
        // Build command details for the prompt
        List<Map<String, Object>> commandSummaries = new ArrayList<>();
        for (Map<String, Object> cmd : commands) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("code", cmd.get("code"));
            summary.put("modelCode", cmd.get("model_code"));
            summary.put("description", cmd.get("description"));
            summary.put("riskLevel", cmd.get("cmd_risk_level"));

            // Parse execution_config for state transitions, side effects, preconditions
            Object execConfigRaw = cmd.get("execution_config");
            if (execConfigRaw != null) {
                try {
                    Map<String, Object> execConfig;
                    if (execConfigRaw instanceof String s) {
                        execConfig = objectMapper.readValue(s, Map.class);
                    } else if (execConfigRaw instanceof Map) {
                        execConfig = (Map<String, Object>) execConfigRaw;
                    } else {
                        execConfig = objectMapper.convertValue(execConfigRaw, Map.class);
                    }

                    // Extract key fields
                    extractIfPresent(execConfig, summary, "type");
                    extractIfPresent(execConfig, summary, "autoSetFields");
                    extractIfPresent(execConfig, summary, "sideEffects");
                    extractIfPresent(execConfig, summary, "preconditions");

                    // State transition info
                    Object stateCheck = execConfig.get("stateCheck");
                    if (stateCheck instanceof Map<?, ?> sc) {
                        summary.put("stateField", sc.get("stateField"));
                        summary.put("fromStates", sc.get("fromStates"));
                        summary.put("toState", sc.get("toState"));
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse execution_config for {}: {}", cmd.get("code"), e.getMessage());
                }
            }

            // Parse input_schema for field names
            Object inputSchemaRaw = cmd.get("input_schema");
            if (inputSchemaRaw != null) {
                try {
                    Map<String, Object> inputSchema;
                    if (inputSchemaRaw instanceof String s) {
                        inputSchema = objectMapper.readValue(s, Map.class);
                    } else if (inputSchemaRaw instanceof Map) {
                        inputSchema = (Map<String, Object>) inputSchemaRaw;
                    } else {
                        inputSchema = objectMapper.convertValue(inputSchemaRaw, Map.class);
                    }
                    Object fields = inputSchema.get("fields");
                    if (fields instanceof List<?> fieldList) {
                        List<String> fieldNames = new ArrayList<>();
                        for (Object f : fieldList) {
                            if (f instanceof Map<?, ?> fm) {
                                String name = (String) fm.get("fieldCode");
                                if (name == null) name = (String) fm.get("code");
                                if (name != null) fieldNames.add(name);
                            }
                        }
                        if (!fieldNames.isEmpty()) {
                            summary.put("inputFields", fieldNames);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse input_schema for {}: {}", cmd.get("code"), e.getMessage());
                }
            }

            commandSummaries.add(summary);
        }

        // Build LLM request
        String commandsJson;
        try {
            commandsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(commandSummaries);
        } catch (Exception e) {
            log.error("Failed to serialize command summaries", e);
            return 0;
        }

        String systemPrompt = """
                You are a technical writer generating concise, agent-friendly descriptions for business operations.
                For each command, generate a JSON array where each element has:
                - "code": the command code
                - "hint": 1-3 sentence description of what the command does, for an AI agent
                - "precondition": 1 sentence describing when this command can be executed (or null if no preconditions)
                - "side_effect": 1 sentence describing what other data changes as a result (or null if none)
                - "output": 1 sentence describing what the command returns (or null if standard record)
                Return ONLY a JSON array, no markdown wrapping.
                """;

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(systemPrompt)
                .maxTokens(4096)
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content("Generate descriptions for the following commands:\n\n" + commandsJson)
                                .build()
                ))
                .build();

        LlmChatResponse response;
        try {
            response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
        } catch (Exception e) {
            log.error("LLM call failed for batch of {} commands: {}", commands.size(), e.getMessage());
            return 0;
        }

        // Extract text from response
        String responseText = "";
        if (response.getContent() != null) {
            for (LlmChatResponse.ContentBlock block : response.getContent()) {
                if ("text".equals(block.getType()) && block.getText() != null) {
                    responseText = block.getText().trim();
                }
            }
        }

        log.info("LLM hint generation: input={} tokens, output={} tokens",
                response.getInputTokens(), response.getOutputTokens());

        // Parse JSON array from response using robust fallback strategy
        List<Map<String, Object>> hints = parseResponse(responseText);
        if (hints.isEmpty()) {
            log.warn("Failed to parse LLM response as JSON array for batch of {} commands", commands.size());
            return 0;
        }

        // Build lookup map by command code
        Map<String, Map<String, Object>> hintMap = new HashMap<>();
        for (Map<String, Object> entry : hints) {
            String code = String.valueOf(entry.get("code"));
            if (code != null && !"null".equals(code)) {
                hintMap.put(code, entry);
            }
        }

        // Update each command's description fields
        int updated = 0;
        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            String pid = (String) cmd.get("pid");
            Long id = cmd.get("id") instanceof Number n ? n.longValue() : null;

            Map<String, Object> entry = hintMap.get(code);
            if (entry == null) {
                log.debug("No hint generated for command: {}", code);
                continue;
            }

            String hint = nullIfBlank(String.valueOf(entry.get("hint")));
            if (hint == null) {
                log.debug("Empty hint generated for command: {}", code);
                continue;
            }

            String precondition = nullIfBlank(String.valueOf(entry.get("precondition")));
            String sideEffect = nullIfBlank(String.valueOf(entry.get("side_effect")));
            String output = nullIfBlank(String.valueOf(entry.get("output")));

            // Log quality metrics
            String existingHint = (String) cmd.get("agent_hint");
            int beforeLen = (existingHint != null) ? existingHint.length() : 0;
            int afterLen = hint.length();
            log.info("Enhanced command {}: hint {} → {} chars (+{})", code, beforeLen, afterLen, afterLen - beforeLen);

            try {
                Map<String, Object> updates = new LinkedHashMap<>();
                updates.put("agent_hint", hint);
                updates.put("precondition_description", precondition);
                updates.put("side_effect_description", sideEffect);
                updates.put("output_description", output);

                dynamicDataMapper.update("ab_command_definition", updates, Map.of("pid", pid));
                updated++;

                // Trigger capability view sync if we have the numeric ID
                if (id != null) {
                    try {
                        capabilityViewService.syncSingleCapability(tenantId, "command", id);
                        log.debug("Synced capability for command {}", code);
                    } catch (Exception e) {
                        log.warn("Failed to sync capability for command {}: {}", code, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to update descriptions for command {}: {}", code, e.getMessage());
            }
        }

        return updated;
    }

    /**
     * Parse LLM response into a list of hint maps with robust fallback strategies.
     * Strategy 1: direct JSON parse
     * Strategy 2: strip markdown code fences then parse
     * Strategy 3: regex extract first JSON array then parse
     */
    List<Map<String, Object>> parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        String json = response.strip();

        // Try 1: direct parse
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e1) {
            // Try 2: strip markdown code blocks
            json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").strip();
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception e2) {
                // Try 3: regex extract JSON array
                Matcher matcher = Pattern.compile("\\[\\s*\\{.*}\\s*]", Pattern.DOTALL).matcher(response);
                if (matcher.find()) {
                    try {
                        return objectMapper.readValue(matcher.group(), new TypeReference<>() {});
                    } catch (Exception e3) {
                        log.warn("All JSON parsing attempts failed for LLM response");
                    }
                }
                return List.of();
            }
        }
    }

    private void extractIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Return null if the string is blank or the literal string "null".
     */
    private String nullIfBlank(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        return value;
    }
}
