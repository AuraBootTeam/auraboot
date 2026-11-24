package com.auraboot.framework.openplatform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded HTTP capture. Raw bytes are request-local and never persisted or logged. */
public final class OpenApiHttpCapture {
    public static final String TARGET = OpenApiHttpCapture.class.getName() + ".target";
    public static final int LIMIT = 64 * 1024;
    private static final String MASK = "[REDACTED]";
    private static final Set<String> HEADERS = Set.of("content-type", "content-length", "x-request-id", "retry-after");
    private final ObjectMapper mapper;
    private final List<String> secrets = new ArrayList<>();

    public OpenApiHttpCapture(ObjectMapper mapper) { this.mapper = mapper; }
    public record Target(String pid, Long tenantId, String installationPid) { }

    public ObjectNode snapshot(Request request, Response response, boolean completed) throws IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) secrets.add(authorization.substring(7));
        JsonNode requestJson = parse(request.getContentAsByteArray());
        collectSecrets(requestJson, 0);
        ObjectNode query = mapper.createObjectNode();
        String rawQuery = request.getQueryString();
        if (rawQuery != null && rawQuery.length() <= LIMIT) {
            try {
                for (String pair : rawQuery.split("&")) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    if (sensitive(key) && !value.isEmpty()) secrets.add(value);
                    query.withArray(key).add(sensitive(key) ? MASK : value);
                }
            } catch (IllegalArgumentException malformedQuery) {
                query.removeAll();
                query.put("captureState", "invalid_query");
            }
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 1); root.put("limitBytes", LIMIT);
        root.set("requestHeaders", headers(request));
        root.set("responseHeaders", headers(response));
        root.set("query", redact(query, 0));
        root.put("queryState", rawQuery != null && rawQuery.length() > LIMIT ? "truncated" : "captured");
        root.set("request", body(request.getContentAsByteArray(), request.overflow,
                request.getContentType(), requestJson));
        if (request.getContentAsByteArray().length == 0 && request.getContentLengthLong() > 0)
            root.set("request", mapper.createObjectNode().put("state", "not_captured").put("reason", "request_body_not_read"));
        root.set("response", completed ? body(response.bytes.toByteArray(), response.count > LIMIT,
                response.getContentType(), parse(response.bytes.toByteArray()))
                : mapper.createObjectNode().put("state", "not_captured").put("reason", "incomplete_response"));
        return root;
    }

    private ObjectNode headers(HttpServletRequest request) {
        ObjectNode node = mapper.createObjectNode();
        for (String name : HEADERS) if (request.getHeader(name) != null) node.put(name, clean(request.getHeader(name)));
        if (request.getHeader("Authorization") != null) node.put("authorization", MASK);
        if (request.getHeader("Cookie") != null) node.put("cookie", MASK);
        return node;
    }
    private ObjectNode headers(HttpServletResponse response) {
        ObjectNode node = mapper.createObjectNode();
        for (String name : HEADERS) if (response.getHeader(name) != null) node.put(name, clean(response.getHeader(name)));
        if (response.getHeader("Set-Cookie") != null) node.put("set-cookie", MASK);
        return node;
    }
    private ObjectNode body(byte[] bytes, boolean overflow, String type, JsonNode parsed) {
        ObjectNode node = mapper.createObjectNode();
        node.put("contentType", type == null ? "" : type);
        node.put("capturedBytes", bytes.length);
        if (overflow) return node.put("state", "truncated").put("reason", "capture_limit_exceeded");
        if (bytes.length == 0) return node.put("state", "empty");
        if (type == null || !(type.toLowerCase(Locale.ROOT).contains("application/json")
                || type.toLowerCase(Locale.ROOT).contains("+json")))
            return node.put("state", "not_captured").put("reason", "unsupported_content_type");
        if (parsed == null) return node.put("state", "not_captured").put("reason", "invalid_json");
        node.put("state", "captured");
        node.set("body", redact(parsed, 0));
        return node;
    }
    private JsonNode parse(byte[] bytes) {
        if (bytes.length == 0) return null;
        try { return mapper.readTree(bytes); }
        catch (IOException invalidJson) { return null; }
    }
    private void collectSecrets(JsonNode value, int depth) {
        if (value == null || depth > 32) return;
        if (value.isObject()) value.properties().forEach(entry -> {
            if (sensitive(entry.getKey()) && entry.getValue().isValueNode()) {
                String secret = entry.getValue().asText();
                if (!secret.isEmpty()) secrets.add(secret);
            } else collectSecrets(entry.getValue(), depth + 1);
        });
        else if (value.isArray()) value.forEach(item -> collectSecrets(item, depth + 1));
    }
    private JsonNode redact(JsonNode value, int depth) {
        if (depth > 32) return mapper.getNodeFactory().textNode(MASK);
        if (value.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            value.properties().forEach(entry -> copy.set(clean(entry.getKey()), sensitive(entry.getKey())
                    ? mapper.getNodeFactory().textNode(MASK) : redact(entry.getValue(), depth + 1)));
            return copy;
        }
        if (value.isArray()) {
            var copy = mapper.createArrayNode(); value.forEach(item -> copy.add(redact(item, depth + 1))); return copy;
        }
        return value.isTextual() ? mapper.getNodeFactory().textNode(clean(value.textValue())) : value.deepCopy();
    }
    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.matches(".*(password|passwd|pwd|secret|token|authorization|cookie|session|apikey|accesskey|credential|email|phone|mobile|contact|idcard|identitynumber|nationalid|passport|ssn|bankaccount|creditcard|cvv|privatekey|signature).*");
    }
    private String clean(String input) {
        String text = input;
        for (String secret : secrets) if (secret.length() >= 4) text = text.replace(secret, MASK);
        return text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer " + MASK)
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", MASK)
                .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", MASK);
    }

    public static final class Request extends ContentCachingRequestWrapper {
        private boolean overflow;
        public Request(HttpServletRequest request) { super(request, LIMIT); }
        @Override protected void handleContentOverflow(int limit) { overflow = true; }
    }
    public static final class Response extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long count;
        private ServletOutputStream output;
        private PrintWriter writer;
        public Response(HttpServletResponse response) { super(response); }
        @Override public ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                ServletOutputStream delegate = super.getOutputStream();
                output = new ServletOutputStream() {
                    @Override public boolean isReady() { return delegate.isReady(); }
                    @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                    @Override public void write(int value) throws IOException { delegate.write(value); if (count++ < LIMIT) bytes.write(value); }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        delegate.write(b, off, len);
                        int capture = (int) Math.min(len, Math.max(0, LIMIT - count));
                        if (capture > 0) bytes.write(b, off, capture); count += len;
                    }
                    @Override public void flush() throws IOException { delegate.flush(); }
                };
            }
            return output;
        }
        @Override public PrintWriter getWriter() throws IOException {
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
            return writer;
        }
        public void finishCapture() { if (writer != null) writer.flush(); }
        @Override public void resetBuffer() { super.resetBuffer(); bytes.reset(); count = 0; }
        @Override public void reset() { super.reset(); bytes.reset(); count = 0; writer = null; output = null; }
        @Override public void flushBuffer() throws IOException { finishCapture(); super.flushBuffer(); }
    }
}
