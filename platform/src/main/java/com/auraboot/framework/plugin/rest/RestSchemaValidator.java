package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-validates a plugin REST request body against the route's declared JSON Schema
 * ({@link com.auraboot.framework.plugin.extension.RestRoute#requestJsonSchema()}). Mirrors the
 * command pipeline's schema gate (and the AuraBot SkillRequestValidator) using the same
 * {@code com.networknt} validator already on the platform classpath.
 *
 * <p>Compiled schemas are cached by their source string so a hot route does not recompile per
 * request. A blank schema is a no-op (route opted out). A malformed body or a schema violation
 * raises {@link ValidationException} → the dispatcher maps it to HTTP 400.
 */
@Slf4j
@Component
public class RestSchemaValidator {

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ConcurrentHashMap<String, JsonSchema> compiledCache = new ConcurrentHashMap<>();

    public RestSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param body   raw request body bytes (may be empty)
     * @param schema the route's JSON Schema source; blank/null skips validation
     * @throws ValidationException on malformed JSON or a schema violation (message carries the
     *                             first offending JSON pointer)
     */
    public void validate(byte[] body, String schema) {
        if (!StringUtils.hasText(schema)) {
            return;
        }
        JsonSchema compiled = compiledCache.computeIfAbsent(schema, schemaFactory::getSchema);

        JsonNode node;
        try {
            node = (body == null || body.length == 0)
                    ? objectMapper.nullNode()
                    : objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "request body is not valid JSON: " + e.getMessage());
        }

        Set<ValidationMessage> errors = compiled.validate(node);
        if (!errors.isEmpty()) {
            ValidationMessage first = errors.iterator().next();
            String pointer = first.getInstanceLocation() == null ? null : first.getInstanceLocation().toString();
            String detail = first.getMessage();
            String composed = StringUtils.hasText(pointer)
                    ? "request body invalid at " + pointer + ": " + detail
                    : "request body invalid: " + detail;
            throw new ValidationException(ResponseCode.CommonValidationFailed, composed);
        }
    }

    /** Number of distinct schemas currently compiled+cached (test/observability aid). */
    public int cachedSchemaCount() {
        return compiledCache.size();
    }
}
