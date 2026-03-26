package com.auraboot.framework.meta.handler;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in command handler that starts a BPM approval process.
 *
 * <p>This handler is triggered via DSL command bindings to integrate dynamic
 * model records with the BPM workflow engine. When executed, it:
 * <ol>
 *   <li>Parses the binding rule config to extract approval settings</li>
 *   <li>Optionally updates a state field on the record to PENDING_APPROVAL</li>
 *   <li>Starts a BPM process instance with the record's business context</li>
 * </ol>
 *
 * <p>Binding rule config format (JSON in ab_binding_rule.config):
 * <pre>{@code
 * {
 *   "approvalProcessKey": "simple-approval",
 *   "approvalTitle": "Contract Approval: ${cc_contract_name}",
 *   "stateField": "cc_contract_status"
 * }
 * }</pre>
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Slf4j
@Component("builtinStartApprovalHandler")
@RequiredArgsConstructor
public class BuiltinStartApprovalHandler implements CommandHandler {

    private static final String DEFAULT_PROCESS_KEY = "simple-approval";
    private static final String PENDING_APPROVAL_STATE = "pending_approval";
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final BpmIntegrationService bpmIntegrationService;
    private final DynamicDataService dynamicDataService;
    private final ObjectMapper objectMapper;

    @Override
    public String getHandlerName() {
        return "builtinStartApprovalHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        log.info("BuiltinStartApprovalHandler executing for command: {}, model: {}",
                context.getCommandCode(), context.getModelCode());

        // 1. Parse ruleConfig JSON
        Map<String, Object> config = parseConfig(context.getRuleConfig());

        String processKey = (String) config.getOrDefault("approvalProcessKey", DEFAULT_PROCESS_KEY);
        String titleTemplate = (String) config.getOrDefault("approvalTitle",
                "Approval: " + context.getModelCode());
        String stateField = (String) config.get("stateField");

        String recordId = context.getTargetRecordId();
        String modelCode = context.getModelCode();

        if (!StringUtils.hasText(recordId)) {
            // Try to get from payload
            if (context.getPayload() != null && context.getPayload().containsKey("pid")) {
                recordId = String.valueOf(context.getPayload().get("pid"));
            }
        }
        if (!StringUtils.hasText(recordId)) {
            throw new BusinessException("Target record ID is required for approval submission");
        }

        // 2. Update record status to PENDING_APPROVAL if stateField is configured
        if (StringUtils.hasText(stateField)) {
            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(stateField, PENDING_APPROVAL_STATE);
            dynamicDataService.update(modelCode, recordId, stateUpdate);
            log.info("Updated record state: model={}, recordId={}, field={} -> {}",
                    modelCode, recordId, stateField, PENDING_APPROVAL_STATE);
        }

        // 3. Build business data for BPM process
        Map<String, Object> businessData = new HashMap<>();
        businessData.put("modelCode", modelCode);
        businessData.put("recordId", recordId);
        businessData.put("initiator", String.valueOf(context.getUserId()));
        businessData.put("commandCode", context.getCommandCode());
        if (StringUtils.hasText(stateField)) {
            businessData.put("stateField", stateField);
        }
        if (context.getPayload() != null) {
            businessData.putAll(context.getPayload());
        }

        // 4. Resolve title template
        String title = resolveTitle(titleTemplate, context.getPayload());

        // 5. Start BPM process
        String businessKey = modelCode + ":" + recordId;
        bpmIntegrationService.startBusinessProcess(processKey, businessKey, businessData, title);

        log.info("BPM approval process started: processKey={}, businessKey={}, title={}",
                processKey, businessKey, title);

        Map<String, Object> result = new HashMap<>();
        result.put("handlerExecuted", true);
        result.put("action", "start_approval");
        result.put("processKey", processKey);
        result.put("businessKey", businessKey);
        return result;
    }

    /**
     * Parse the ruleConfig JSON string into a Map.
     * Returns empty map if input is null or invalid.
     */
    private Map<String, Object> parseConfig(String ruleConfig) {
        if (!StringUtils.hasText(ruleConfig)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(ruleConfig, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse ruleConfig JSON: {}", ruleConfig, e);
            return new HashMap<>();
        }
    }

    /**
     * Resolve a title template by replacing ${fieldName} placeholders with payload values.
     *
     * @param template e.g. "Contract Approval: ${cc_contract_name}"
     * @param payload  command payload with field values
     * @return resolved title string
     */
    private String resolveTitle(String template, Map<String, Object> payload) {
        if (template == null) {
            return "Approval";
        }
        if (payload == null || payload.isEmpty()) {
            return template.replaceAll("\\$\\{[^}]+}", "");
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String fieldName = matcher.group(1).trim();
            Object value = payload.get(fieldName);
            String replacement = value != null ? Matcher.quoteReplacement(value.toString()) : "";
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
