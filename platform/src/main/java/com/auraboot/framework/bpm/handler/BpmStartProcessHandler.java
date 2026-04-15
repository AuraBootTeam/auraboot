package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic command handler that starts a BPM process instance.
 *
 * <p>Payload contract:
 * <ul>
 *   <li>{@code processKey} (required)</li>
 *   <li>{@code businessKey} (required) — typically the record pid</li>
 *   <li>{@code variables} (optional) — process variables</li>
 *   <li>{@code title} (optional) — defaults to {@code processKey-businessKey}</li>
 * </ul>
 *
 * <p>Output: {@code {processInstanceId: "..."}}. The caller is expected to persist
 * the id via a subsequent update-record step; this handler does not couple to
 * record persistence.
 *
 * @since 7.3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmStartProcessHandler implements CommandHandlerExtension {

    public static final String COMMAND_CODE = "bpm:start-process";

    public static final String ARG_PROCESS_KEY = "processKey";
    public static final String ARG_BUSINESS_KEY = "businessKey";
    public static final String ARG_VARIABLES = "variables";
    public static final String ARG_TITLE = "title";

    public static final String RESULT_PROCESS_INSTANCE_ID = "processInstanceId";

    public static final String ERR_PROCESS_KEY_REQUIRED = "bpm.process.process_key_required";
    public static final String ERR_BUSINESS_KEY_REQUIRED = "bpm.process.business_key_required";
    public static final String ERR_START_FAILED = "bpm.process.start_failed";

    private final BpmIntegrationService bpmIntegrationService;

    @Override
    public String getCommandType() {
        return COMMAND_CODE;
    }

    @Override
    public Object execute(CommandContext context) {
        Map<String, Object> payload = context.payload() != null ? context.payload() : Map.of();

        String processKey = asNonBlankString(payload.get(ARG_PROCESS_KEY));
        if (processKey == null) {
            throw new BusinessException(ERR_PROCESS_KEY_REQUIRED);
        }
        String businessKey = asNonBlankString(payload.get(ARG_BUSINESS_KEY));
        if (businessKey == null) {
            throw new BusinessException(ERR_BUSINESS_KEY_REQUIRED);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = payload.get(ARG_VARIABLES) instanceof Map
                ? new HashMap<>((Map<String, Object>) payload.get(ARG_VARIABLES))
                : new HashMap<>();

        String title = asNonBlankString(payload.get(ARG_TITLE));
        if (title == null) {
            title = processKey + "-" + businessKey;
        }

        ProcessInstance instance;
        try {
            instance = bpmIntegrationService.startBusinessProcess(processKey, businessKey, variables, title);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Failed to start BPM process: key={}, businessKey={}, error={}",
                    processKey, businessKey, e.getMessage(), e);
            throw new BusinessException(ERR_START_FAILED);
        }

        if (instance == null || instance.getInstanceId() == null) {
            throw new BusinessException(ERR_START_FAILED);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(RESULT_PROCESS_INSTANCE_ID, instance.getInstanceId());
        return result;
    }

    private static String asNonBlankString(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
