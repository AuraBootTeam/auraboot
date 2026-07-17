package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutionException;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.notification.sms.SmsSendResult;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SendSmsActionExecutor implements ActionExecutor {

    private static final String PHONE_PREFIX = "PHONE:";
    private static final String DEFAULT_TEMPLATE = "direct_message";
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{6,20}$");

    private final SmsSenderRouter smsSenderRouter;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        String target = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("target"), context),
                AutomationActionValueResolver.resolveString(config.get("recipient"), context),
                AutomationActionValueResolver.resolveString(config.get("recipients"), context));
        if (target == null) {
            throw new IllegalArgumentException("send_sms action requires target");
        }
        String content = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("content"), context),
                AutomationActionValueResolver.resolveString(config.get("message"), context));
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("send_sms action requires content");
        }
        String title = AutomationActionValueResolver.resolveString(config.get("title"), context);
        String template = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("template"), context),
                AutomationActionValueResolver.resolveString(config.get("templateCode"), context),
                DEFAULT_TEMPLATE);

        List<String> targetPhones = normalizePhoneTargets(target);
        List<String> providers = new ArrayList<>();
        List<String> messageIds = new ArrayList<>();
        Map<String, String> params = smsParams(content, title, context);

        for (String phone : targetPhones) {
            SmsSenderRouter.RoutedSmsResult routed;
            try {
                routed = smsSenderRouter.sendWithRealProvider(phone, template, params);
            } catch (RuntimeException error) {
                throw smsFailure(messageOf(error), template, targetPhones,
                        providers, messageIds, context, error);
            }
            SmsSendResult sendResult = routed.sendResult();
            if (routed.providerCode() != null && !routed.providerCode().isBlank()) {
                providers.add(routed.providerCode());
            }
            if (sendResult == null || !sendResult.isSuccess()) {
                String error = sendResult != null ? sendResult.getErrorMessage() : "empty result";
                String message = "send_sms failed via " + routed.providerCode() + ": " + error;
                throw smsFailure(message, template, targetPhones, providers, messageIds, context,
                        new IllegalStateException(message));
            }
            if (sendResult.getMessageId() != null) {
                messageIds.add(sendResult.getMessageId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("channel", "sms");
        result.put("template", template);
        result.put("sentCount", messageIds.size());
        result.put("targetPhones", targetPhones);
        result.put("messageIds", messageIds);
        result.put("providers", providers);
        if (!providers.isEmpty()) {
            result.put("provider", providers.get(0));
        }
        putIfPresent(result, "modelCode", context.get("modelCode"));
        putIfPresent(result, "recordPid", context.get("recordPid"));
        return result;
    }

    @Override
    public boolean supports(String actionType) {
        return "send_sms".equals(actionType);
    }

    private static Map<String, String> smsParams(String content, String title, Map<String, Object> context) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("content", content);
        if (title != null && !title.isBlank()) {
            params.put("title", title);
        }
        putString(params, "automationPid", context.get("automationPid"));
        putString(params, "modelCode", context.get("modelCode"));
        putString(params, "recordPid", context.get("recordPid"));
        return params;
    }

    private static List<String> normalizePhoneTargets(String raw) {
        LinkedHashSet<String> phones = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith(PHONE_PREFIX)) {
                value = value.substring(PHONE_PREFIX.length()).trim();
            }
            if (!PHONE_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "send_sms target must be a phone number or PHONE:<number>: " + token.trim());
            }
            phones.add(value);
        }
        if (phones.isEmpty()) {
            throw new IllegalArgumentException("send_sms target resolved no phone numbers");
        }
        return List.copyOf(phones);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void putString(Map<String, String> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            map.put(key, String.valueOf(value));
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static ActionExecutionException smsFailure(String message, String template,
                                                       List<String> targetPhones,
                                                       List<String> providers,
                                                       List<String> messageIds,
                                                       Map<String, Object> context,
                                                       Throwable cause) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("channel", "sms");
        result.put("template", template);
        result.put("sentCount", messageIds.size());
        result.put("targetPhones", targetPhones);
        result.put("messageIds", List.copyOf(messageIds));
        result.put("providers", List.copyOf(providers));
        if (!providers.isEmpty()) {
            result.put("provider", providers.get(0));
        }
        result.put("failureReason", "sms_delivery_failed");
        result.put("errorMessage", message);
        putIfPresent(result, "modelCode", context.get("modelCode"));
        putIfPresent(result, "recordPid", context.get("recordPid"));
        return new ActionExecutionException(message, result, cause);
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : error.getClass().getSimpleName();
    }
}
