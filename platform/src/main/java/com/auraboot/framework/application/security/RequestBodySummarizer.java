package com.auraboot.framework.application.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Produces a redacted JSON summary of HTTP request bodies for audit logging:
 * only the top-level keys are recorded, never values. GET/DELETE requests and
 * non-cached requests yield {@code null}.
 *
 * <p>Output format: {@code {"keys":["a","b","c"]}}, capped at 2048 chars. On
 * parse failure or non-object root, returns {@code {"parse_error":true}}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RequestBodySummarizer {

    private static final int MAX_LEN = 2048;
    private static final String PARSE_ERROR = "{\"parse_error\":true}";

    private final ObjectMapper objectMapper;

    public String summarize(HttpServletRequest req) {
        String method = req.getMethod();
        if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            return null;
        }
        if (!(req instanceof ContentCachingRequestWrapper wrapper)) {
            return null;
        }
        byte[] content = wrapper.getContentAsByteArray();
        if (content.length == 0) return null;

        try {
            JsonNode root = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return PARSE_ERROR;
            }
            List<String> keys = new ArrayList<>();
            Iterator<String> it = root.fieldNames();
            while (it.hasNext()) keys.add(it.next());

            String result = objectMapper.writeValueAsString(Map.of("keys", keys));
            return result.length() > MAX_LEN ? result.substring(0, MAX_LEN) : result;
        } catch (Exception e) {
            log.debug("body summarize failed: {}", e.getMessage());
            return PARSE_ERROR;
        }
    }
}
