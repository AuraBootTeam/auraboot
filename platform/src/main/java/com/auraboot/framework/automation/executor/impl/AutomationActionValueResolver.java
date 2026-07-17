package com.auraboot.framework.automation.executor.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AutomationActionValueResolver {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private AutomationActionValueResolver() {
    }

    static String resolveString(Object raw, Map<String, Object> context) {
        Object value = resolveValue(raw, context);
        return value != null ? String.valueOf(value) : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolveMap(Object raw, Map<String, Object> context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return resolved;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            resolved.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), context));
        }
        return resolved;
    }

    static Object resolveValue(Object raw, Map<String, Object> context) {
        if (raw instanceof Map<?, ?>) {
            return resolveMap(raw, context);
        }
        if (raw instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : list) {
                resolved.add(resolveValue(item, context));
            }
            return resolved;
        }
        if (!(raw instanceof String text)) {
            return raw;
        }
        Matcher exact = TEMPLATE.matcher(text);
        if (exact.matches()) {
            return resolvePath(exact.group(1), context);
        }
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object value = resolvePath(matcher.group(1), context);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? String.valueOf(value) : ""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolvePath(String path, Map<String, Object> context) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
