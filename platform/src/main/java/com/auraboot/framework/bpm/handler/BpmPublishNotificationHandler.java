package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.notification.dto.NotificationSendRequest;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic command handler that publishes a templated notification.
 *
 * <p>Payload contract:
 * <ul>
 *   <li>{@code eventCode} (required) — the notification template code.</li>
 *   <li>{@code recipientUserId} (required if {@code recipientFrom} absent) —
 *       explicit recipient id. Wins over {@code recipientFrom}.</li>
 *   <li>{@code recipientFrom} (optional) — one of {@code "applicant"} or
 *       {@code "assignee"}. When set, the handler resolves the user id from
 *       matching payload keys: {@code applicantUserId} / {@code initiatorUserId} /
 *       {@code startUserId} for {@code applicant}; {@code assigneeUserId} for
 *       {@code assignee}.</li>
 *   <li>{@code templateParams} (optional) — variables merged into the notification
 *       template.</li>
 *   <li>{@code channels} (optional) — reserved for future multi-channel routing.
 *       Present only for forward compatibility; the current implementation
 *       always delegates to {@link NotificationService#send(NotificationSendRequest)},
 *       which itself resolves channel configuration from the template code.</li>
 * </ul>
 *
 * <p>Output: {@code {notificationId: "..."}} where the value mirrors the
 * eventCode + recipient for traceability (NotificationService does not currently
 * return an id from {@code send(request)}; we fabricate a stable tracking id
 * only — not a DB key).
 *
 * @since 7.3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmPublishNotificationHandler implements CommandHandlerExtension {

    public static final String COMMAND_CODE = "bpm:publish-notification";

    public static final String ARG_EVENT_CODE = "eventCode";
    public static final String ARG_RECIPIENT_USER_ID = "recipientUserId";
    public static final String ARG_RECIPIENT_FROM = "recipientFrom";
    public static final String ARG_TEMPLATE_PARAMS = "templateParams";
    public static final String ARG_CHANNELS = "channels";

    public static final String RECIPIENT_FROM_APPLICANT = "applicant";
    public static final String RECIPIENT_FROM_ASSIGNEE = "assignee";

    /** Payload keys that hold the applicant/initiator user id, tried in order. */
    private static final String[] APPLICANT_KEYS = {"applicantUserId", "initiatorUserId", "startUserId"};

    /** Payload key that holds the assignee user id. */
    private static final String ASSIGNEE_KEY = "assigneeUserId";

    public static final String RESULT_NOTIFICATION_ID = "notificationId";

    public static final String ERR_EVENT_CODE_REQUIRED = "bpm.notification.event_code_required";
    public static final String ERR_RECIPIENT_UNRESOLVED = "bpm.notification.recipient_unresolved";
    public static final String ERR_RECIPIENT_FROM_INVALID = "bpm.notification.recipient_from_invalid";
    public static final String ERR_SEND_FAILED = "bpm.notification.send_failed";

    private final NotificationService notificationService;

    @Override
    public String getCommandType() {
        return COMMAND_CODE;
    }

    @Override
    public Object execute(CommandContext context) {
        Map<String, Object> payload = context.payload() != null ? context.payload() : Map.of();

        String eventCode = asNonBlankString(payload.get(ARG_EVENT_CODE));
        if (eventCode == null) {
            throw new BusinessException(ERR_EVENT_CODE_REQUIRED);
        }

        String recipientId = resolveRecipient(payload);
        if (recipientId == null) {
            throw new BusinessException(ERR_RECIPIENT_UNRESOLVED);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> templateParams = payload.get(ARG_TEMPLATE_PARAMS) instanceof Map
                ? (Map<String, Object>) payload.get(ARG_TEMPLATE_PARAMS)
                : new HashMap<>();

        NotificationSendRequest request = NotificationSendRequest.builder()
                .templateCode(eventCode)
                .recipientId(recipientId)
                .variables(templateParams)
                .sourceType("bpm")
                .sourceId(context.commandType() != null ? context.commandType() : COMMAND_CODE)
                .build();

        try {
            notificationService.send(request);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Failed to send BPM notification: eventCode={}, recipient={}, error={}",
                    eventCode, recipientId, e.getMessage(), e);
            throw new BusinessException(ERR_SEND_FAILED);
        }

        // NotificationService#send is void — synthesize a tracking id for audit/debug.
        String trackingId = eventCode + ":" + recipientId;
        Map<String, Object> result = new HashMap<>();
        result.put(RESULT_NOTIFICATION_ID, trackingId);
        return result;
    }

    private String resolveRecipient(Map<String, Object> payload) {
        // Explicit id wins.
        String explicit = asNonBlankString(payload.get(ARG_RECIPIENT_USER_ID));
        if (explicit != null) {
            return explicit;
        }

        String from = asNonBlankString(payload.get(ARG_RECIPIENT_FROM));
        if (from == null) {
            return null;
        }
        switch (from.toLowerCase()) {
            case RECIPIENT_FROM_APPLICANT -> {
                for (String key : APPLICANT_KEYS) {
                    String value = asNonBlankString(payload.get(key));
                    if (value != null) return value;
                }
                return null;
            }
            case RECIPIENT_FROM_ASSIGNEE -> {
                return asNonBlankString(payload.get(ASSIGNEE_KEY));
            }
            default -> throw new BusinessException(ERR_RECIPIENT_FROM_INVALID);
        }
    }

    private static String asNonBlankString(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
