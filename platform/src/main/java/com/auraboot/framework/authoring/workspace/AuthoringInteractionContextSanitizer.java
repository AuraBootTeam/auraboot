package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** Keeps only resumable, non-secret interaction context and rejects unsafe external routes. */
@Component
public class AuthoringInteractionContextSanitizer {

    private static final int MAX_CONTEXT_BYTES = 16 * 1024;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "route", "recordPid", "tabId", "filters", "sort", "scroll", "selection",
            "outlinePath");

    private final ObjectMapper objectMapper;

    public AuthoringInteractionContextSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode sanitize(JsonNode supplied) {
        ObjectNode safe = objectMapper.createObjectNode();
        if (supplied == null || supplied.isNull()) {
            return safe;
        }
        if (!supplied.isObject()) {
            throw invalid("authoring.interaction-context.invalid");
        }
        copyAllowed(supplied, safe);
        if (serializedSize(safe) > MAX_CONTEXT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "authoring.interaction-context.too-large");
        }
        JsonNode route = safe.get("route");
        if (route != null && !isSafeInternalRoute(route)) {
            throw invalid("authoring.interaction-context.unsafe-route");
        }
        return safe;
    }

    private void copyAllowed(JsonNode supplied, ObjectNode safe) {
        for (String key : ALLOWED_KEYS) {
            JsonNode value = supplied.get(key);
            if (value != null) {
                safe.set(key, value.deepCopy());
            }
        }
    }

    private int serializedSize(JsonNode value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("authoring.json.serialize-failed", e);
        }
    }

    private boolean isSafeInternalRoute(JsonNode route) {
        if (!route.isTextual()) {
            return false;
        }
        String value = route.asText();
        return !value.isBlank()
                && value.length() <= 240
                && !value.startsWith("//")
                && !value.contains("\\")
                && !value.contains("..")
                && !value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")
                && value.matches("[A-Za-z0-9_/?&=.#:-]+");
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
