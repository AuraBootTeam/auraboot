package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.meta.service.RecordCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AddCommentActionExecutor implements ActionExecutor {

    private final RecordCommentService recordCommentService;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("ADD_COMMENT action requires config");
        }

        String modelCode = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("modelCode"), context),
                AutomationActionValueResolver.resolveString(context.get("modelCode"), context),
                AutomationActionValueResolver.resolveString(context.get("entityCode"), context));
        String recordPid = firstNonBlank(
                AutomationActionValueResolver.resolveString(config.get("recordPid"), context),
                AutomationActionValueResolver.resolveString(context.get("recordPid"), context));
        String content = AutomationActionValueResolver.resolveString(config.get("content"), context);
        String mentions = AutomationActionValueResolver.resolveString(config.get("mentions"), context);

        if (modelCode == null) {
            throw new IllegalArgumentException("ADD_COMMENT action requires modelCode");
        }
        if (recordPid == null) {
            throw new IllegalArgumentException("ADD_COMMENT action requires recordPid");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("ADD_COMMENT action requires content");
        }

        Map<String, Object> comment = recordCommentService.addComment(modelCode, recordPid, content, mentions);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("modelCode", modelCode);
        result.put("recordPid", recordPid);
        result.put("content", content);
        if (mentions != null && !mentions.isBlank()) {
            result.put("mentions", mentions);
        }
        Object commentPid = comment != null ? comment.get("commentPid") : null;
        if (commentPid != null) {
            result.put("commentPid", commentPid);
        }
        return result;
    }

    @Override
    public boolean supports(String actionType) {
        return "add_comment".equals(actionType);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
