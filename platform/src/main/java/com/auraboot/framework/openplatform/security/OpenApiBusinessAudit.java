package com.auraboot.framework.openplatform.security;

import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request-owned public business descriptors; never captures inputs or credentials. */
public final class OpenApiBusinessAudit {
    private static final String ATTRIBUTE = OpenApiBusinessAudit.class.getName();
    private OpenApiBusinessAudit() { }

    public static void capture(HttpServletRequest request, Capability capability) {
        var details = JsonNodeFactory.instance.objectNode();
        details.put("actorKind", "machine");
        details.put("capability", capability.code());
        String[] segments = request.getRequestURI().split("/", -1);
        if (segments.length >= 6 && "resources".equals(segments[4])) {
            details.put("resource", segments[5]);
            details.put("operation", capability.code().substring(capability.code().lastIndexOf('.') + 1));
            if (segments.length == 7 && publicPid(segments[6])) details.put("recordPid", segments[6]);
        } else if (segments.length == 6 && "queries".equals(segments[4]) && segments[5].endsWith(":execute")) {
            details.put("resource", segments[5].substring(0, segments[5].length() - ":execute".length()));
            details.put("operation", "execute");
        } else if (segments.length == 6 && "commands".equals(segments[4])) {
            details.put("operation", "execute");
        }
        request.setAttribute(ATTRIBUTE, details);
    }

    public static void attempt(String resource, String operation, String pid) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return;
        Object captured = attributes.getRequest().getAttribute(ATTRIBUTE);
        if (!(captured instanceof com.fasterxml.jackson.databind.node.ObjectNode details)) return;
        details.put("resource", resource);
        details.put("operation", operation);
        if (publicPid(pid)) details.put("recordPid", pid);
    }

    public static void result(String resource, String operation, String pid, boolean replay) {
        attempt(resource, operation, pid);
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return;
        Object captured = attributes.getRequest().getAttribute(ATTRIBUTE);
        if (captured instanceof com.fasterxml.jackson.databind.node.ObjectNode details) {
            details.put("idempotentReplay", replay);
        }
    }

    public static JsonNode details(HttpServletRequest request) {
        Object captured = request.getAttribute(ATTRIBUTE);
        return captured instanceof JsonNode details ? details.deepCopy()
                : JsonNodeFactory.instance.objectNode().put("actorKind", "machine");
    }

    private static boolean publicPid(String pid) {
        return pid != null && pid.matches("[A-Za-z0-9_-]{1,64}") && !pid.matches("[0-9]+");
    }
}
