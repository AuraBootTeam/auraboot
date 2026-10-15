package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Stable protocol fingerprints; request array order remains meaningful, authority sets do not. */
@Component
@RequiredArgsConstructor
public class OpenApiProtocolHash {
    private final ObjectMapper json;
    private final OpenPlatformSecretCodec secrets;
    public String fingerprint(Object value, boolean unorderedMetadata) {
        try { return secrets.sha256(json.writeValueAsString(canonical(json.valueToTree(value), unorderedMetadata))); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Public protocol value cannot be encoded", invalid); }
    }
    private JsonNode canonical(JsonNode value, boolean unorderedMetadata) {
        if (value.isObject()) {
            var object = json.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> object.set(key, canonical(value.get(key), unorderedMetadata))); return object;
        }
        if (value.isArray()) {
            List<JsonNode> items = new ArrayList<>(); value.forEach(item -> items.add(canonical(item, unorderedMetadata)));
            if (unorderedMetadata) items.sort(Comparator.comparing(JsonNode::toString));
            var array = json.createArrayNode(); items.forEach(array::add); return array;
        }
        return value;
    }
}
