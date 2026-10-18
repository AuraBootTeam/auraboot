package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves scoped field references without evaluating scripts or querying arbitrary models. */
final class ActionValueResolver {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([a-z]+)\\.([^{}]+)}");

    private ActionValueResolver() { }

    static Object resolve(Object value, DecisionContext context) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, child) -> resolved.put(String.valueOf(key), resolve(child, context)));
            return resolved;
        }
        if (value instanceof java.util.List<?> list) {
            var resolved = new ArrayList<>();
            list.forEach(child -> resolved.add(resolve(child, context)));
            return resolved;
        }
        if (!(value instanceof String text)) return value;
        var matcher = REFERENCE.matcher(text);
        if (matcher.matches()) {
            return reference(context, matcher.group(1), matcher.group(2));
        }
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            Object resolved = reference(context, matcher.group(1), matcher.group(2));
            if (resolved == null || resolved instanceof Map<?, ?> || resolved instanceof java.util.List<?>) {
                throw new IllegalArgumentException("A message template requires a scalar field value");
            }
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(String.valueOf(resolved)));
        }
        matcher.appendTail(output);
        if (output.indexOf("${") >= 0) {
            throw new IllegalArgumentException("Unsupported action field reference");
        }
        return output.toString();
    }

    private static Object reference(DecisionContext context, String scope, String path) {
        var value = context.resolve(Scope.fromCode(scope), path);
        if (!value.present()) {
            throw new IllegalArgumentException("Action field reference is unavailable: " + scope + "." + path);
        }
        return value.value();
    }
}
