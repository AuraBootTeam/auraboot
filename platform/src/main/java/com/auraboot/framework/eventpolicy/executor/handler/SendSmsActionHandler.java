package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.notification.sms.SmsSendResult;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SendSmsActionHandler implements ActionHandler {

    private static final String PHONE_PREFIX = "PHONE:";
    private static final String DEFAULT_TEMPLATE = "direct_message";
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{6,20}$");

    private final SmsSenderRouter smsSenderRouter;

    @Override
    public boolean supports(String actionType) {
        return "SEND_SMS".equals(actionType);
    }

    @Override
    public boolean runtimeAvailable() {
        return smsSenderRouter.realSenderAvailability().available();
    }

    @Override
    public List<ActionProviderDependency> runtimeProviderDependencies() {
        SmsSenderRouter.SmsProviderAvailability availability = smsSenderRouter.realSenderAvailability();
        return List.of(new ActionProviderDependency(
                "SMS",
                availability.providerCodes(),
                "真实短信 provider",
                true,
                availability.available(),
                availability.available() ? "AVAILABLE" : "UNAVAILABLE",
                availability.available() ? null : availability.reason()));
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        executeWithResult(plan, context);
    }

    @Override
    public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        String modelCode = resolveRecordString(context, "entityCode");
        String recordPid = resolveRecordString(context, "recordPid");
        String target = firstNonBlank(render(plan.target(), context), render(payload.get("target"), context));
        if (target == null) {
            throw ActionFailurePayload.builder(plan, "action_target_missing")
                    .with("channel", "sms")
                    .with("field", "target")
                    .with("requiredContext", List.of("action.target", "payload.target"))
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("SEND_SMS requires target", null);
        }
        String content = firstNonBlank(render(payload.get("content"), context), render(payload.get("message"), context));
        if (content == null || content.isBlank()) {
            throw ActionFailurePayload.builder(plan, "payload_content_missing")
                    .with("channel", "sms")
                    .with("field", "payload.content")
                    .with("target", target)
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("SEND_SMS requires payload.content", null);
        }
        String title = render(payload.get("title"), context);
        String template = firstNonBlank(
                render(payload.get("template"), context),
                render(payload.get("templateCode"), context),
                DEFAULT_TEMPLATE);

        List<String> targetPhones = normalizePhoneTargets(plan, target, modelCode, recordPid);
        List<String> providers = new ArrayList<>();
        List<String> messageIds = new ArrayList<>();
        Map<String, String> params = smsParams(plan, content, title, modelCode, recordPid);
        Map<String, Object> result = resultPayload(plan, template, targetPhones, providers, messageIds,
                modelCode, recordPid);

        for (String phone : targetPhones) {
            SmsSenderRouter.RoutedSmsResult routed;
            try {
                routed = smsSenderRouter.sendWithRealProvider(phone, template, params);
            } catch (RuntimeException e) {
                throw smsDeliveryFailure(plan, result, null, ActionFailurePayload.messageOf(e), e);
            }
            SmsSendResult sendResult = routed.sendResult();
            if (sendResult == null || !sendResult.isSuccess()) {
                String error = sendResult != null ? sendResult.getErrorMessage() : "empty result";
                throw smsDeliveryFailure(plan, result, routed.providerCode(), error, null);
            }
            providers.add(routed.providerCode());
            if (sendResult.getMessageId() != null) {
                messageIds.add(sendResult.getMessageId());
            }
            result = resultPayload(plan, template, targetPhones, providers, messageIds, modelCode, recordPid);
        }

        return result;
    }

    private static ActionExecutionException smsDeliveryFailure(
            ResolvedActionPlan plan,
            Map<String, Object> result,
            String provider,
            String error,
            RuntimeException cause) {
        return ActionFailurePayload.builder(plan, "sms_delivery_failed")
                .merge(result)
                .with("provider", provider)
                .with("errorMessage", error)
                .exception(provider != null
                        ? "SEND_SMS failed via " + provider + ": " + error
                        : "SEND_SMS failed: " + error,
                        cause);
    }

    private static Map<String, Object> resultPayload(ResolvedActionPlan plan,
                                                     String template,
                                                     List<String> targetPhones,
                                                     List<String> providers,
                                                     List<String> messageIds,
                                                     String modelCode,
                                                     String recordPid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", "sms");
        result.put("template", template);
        result.put("sentCount", messageIds.size());
        result.put("targetPhones", List.copyOf(targetPhones));
        result.put("messageIds", List.copyOf(messageIds));
        result.put("providers", List.copyOf(providers));
        result.put("ruleCode", plan.ruleCode());
        if (!providers.isEmpty()) {
            result.put("provider", providers.get(0));
        }
        if (modelCode != null) {
            result.put("modelCode", modelCode);
        }
        if (recordPid != null) {
            result.put("recordPid", recordPid);
        }
        return result;
    }

    private static Map<String, String> smsParams(ResolvedActionPlan plan, String content, String title,
                                                 String modelCode, String recordPid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("content", content);
        if (title != null && !title.isBlank()) {
            params.put("title", title);
        }
        if (plan.ruleCode() != null && !plan.ruleCode().isBlank()) {
            params.put("ruleCode", plan.ruleCode());
        }
        if (modelCode != null && !modelCode.isBlank()) {
            params.put("modelCode", modelCode);
        }
        if (recordPid != null && !recordPid.isBlank()) {
            params.put("recordPid", recordPid);
        }
        return params;
    }

    private static List<String> normalizePhoneTargets(
            ResolvedActionPlan plan,
            String raw,
            String modelCode,
            String recordPid) {
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
                throw ActionFailurePayload.builder(plan, "target_invalid")
                        .with("channel", "sms")
                        .with("targetType", "PHONE")
                        .with("target", raw)
                        .with("field", "target")
                        .with("invalidTarget", token.trim())
                        .with("modelCode", modelCode)
                        .with("recordPid", recordPid)
                        .exception("SEND_SMS target must be a phone number or PHONE:<number>: " + token.trim(), null);
            }
            phones.add(value);
        }
        if (phones.isEmpty()) {
            throw ActionFailurePayload.builder(plan, "target_resolved_no_phone_numbers")
                    .with("channel", "sms")
                    .with("targetType", "PHONE")
                    .with("target", raw)
                    .with("resolvedCount", 0)
                    .with("modelCode", modelCode)
                    .with("recordPid", recordPid)
                    .exception("SEND_SMS target resolved no phone numbers", null);
        }
        return List.copyOf(phones);
    }

    private static String render(Object value, DecisionContext context) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveToken(matcher.group(1).trim(), context);
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? String.valueOf(resolved) : ""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolveToken(String token, DecisionContext context) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            Scope scope = Scope.fromCode(token.substring(0, dot));
            DecisionContext.PathValue pv = context.resolve(scope, token.substring(dot + 1));
            return pv.present() ? pv.value() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String resolveRecordString(DecisionContext context, String field) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, field);
        return pv.present() && pv.value() != null ? String.valueOf(pv.value()) : null;
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
}
