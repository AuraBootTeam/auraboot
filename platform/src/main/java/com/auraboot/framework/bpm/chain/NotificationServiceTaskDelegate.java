package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.notification.dto.NotificationSendRequest;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin SmartEngine serviceTask delegate that publishes a notification.
 *
 * <p>Wired into BPMN via {@code smart:class="notificationServiceTaskDelegate"}.
 * The node XML carries the following {@code smart:*} extension attributes:
 * <ul>
 *   <li>{@code smart:eventCode} — the notification template code (required).</li>
 *   <li>{@code smart:recipientFrom} — one of {@code "applicant"} or
 *       {@code "assignee"}. Resolves the recipient user id from well-known
 *       process variable keys (see below).</li>
 *   <li>{@code smart:templateParamsVars} — comma-separated list of process
 *       variable names to collect as notification template parameters.</li>
 * </ul>
 *
 * <p>Applicant recipient is resolved from process variables in order:
 * {@code applicantUserId} / {@code initiatorUserId} / {@code startUserId}.
 * Assignee recipient comes from {@code assigneeUserId}.
 *
 * @since 7.3.0
 */
@Slf4j
@Component(BpmServiceTaskConstants.BEAN_NOTIFICATION_DELEGATE)
@RequiredArgsConstructor
public class NotificationServiceTaskDelegate implements JavaDelegation {

    public static final String RECIPIENT_FROM_APPLICANT = "applicant";
    public static final String RECIPIENT_FROM_ASSIGNEE = "assignee";

    public static final String ERR_EVENT_CODE_REQUIRED = "bpm.notification.event_code_required";
    public static final String ERR_RECIPIENT_UNRESOLVED = "bpm.notification.recipient_unresolved";
    public static final String ERR_RECIPIENT_FROM_INVALID = "bpm.notification.recipient_from_invalid";
    public static final String ERR_SEND_FAILED = "bpm.notification.send_failed";

    /** Process variable keys checked (in order) to resolve an applicant recipient. */
    private static final String[] APPLICANT_KEYS = {"applicantUserId", "initiatorUserId", "startUserId"};

    /** Process variable key for an assignee recipient. */
    private static final String ASSIGNEE_KEY = "assigneeUserId";

    private final NotificationService notificationService;

    @Override
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
            executionContext.setRequest(processVars);
        }

        Map<String, String> properties = resolveProperties(executionContext);

        String eventCode = properties.get(BpmServiceTaskConstants.ATTR_EVENT_CODE);
        if (eventCode == null || eventCode.isBlank()) {
            throw new BusinessException(ERR_EVENT_CODE_REQUIRED);
        }

        String recipientFrom = properties.get(BpmServiceTaskConstants.ATTR_RECIPIENT_FROM);
        String recipientId = resolveRecipient(recipientFrom, processVars);
        if (recipientId == null) {
            throw new BusinessException(ERR_RECIPIENT_UNRESOLVED);
        }

        Map<String, Object> templateParams = buildTemplateParams(
                properties.get(BpmServiceTaskConstants.ATTR_TEMPLATE_PARAMS_VARS), processVars);

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode(eventCode)
                .recipientId(recipientId)
                .variables(templateParams)
                .sourceType("bpm")
                .sourceId(resolveActivityId(executionContext))
                .build();

        try {
            notificationService.send(request);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("BPM notification serviceTask failed: eventCode={}, recipient={}, error={}",
                    eventCode, recipientId, e.getMessage(), e);
            throw new BusinessException(ERR_SEND_FAILED);
        }
    }

    private String resolveRecipient(String recipientFrom, Map<String, Object> processVars) {
        if (recipientFrom == null || recipientFrom.isBlank()) {
            return null;
        }
        switch (recipientFrom.toLowerCase()) {
            case RECIPIENT_FROM_APPLICANT -> {
                for (String key : APPLICANT_KEYS) {
                    Object value = processVars.get(key);
                    if (value != null && !value.toString().isBlank()) {
                        return value.toString();
                    }
                }
                return null;
            }
            case RECIPIENT_FROM_ASSIGNEE -> {
                Object value = processVars.get(ASSIGNEE_KEY);
                return (value != null && !value.toString().isBlank()) ? value.toString() : null;
            }
            default -> throw new BusinessException(ERR_RECIPIENT_FROM_INVALID);
        }
    }

    private Map<String, Object> buildTemplateParams(String templateParamsVars,
                                                    Map<String, Object> processVars) {
        Map<String, Object> params = new HashMap<>();
        if (templateParamsVars == null || templateParamsVars.isBlank()) {
            return params;
        }
        for (String rawName : templateParamsVars.split(",")) {
            String name = rawName.trim();
            if (name.isEmpty()) continue;
            params.put(name, processVars.get(name));
        }
        return params;
    }

    private Map<String, String> resolveProperties(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased
                && idBased.getProperties() != null) {
            return idBased.getProperties();
        }
        return new HashMap<>();
    }

    private String resolveActivityId(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased) {
            return idBased.getId();
        }
        return "bpm:notification";
    }
}
