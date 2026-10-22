package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class ToolArgumentPolicy {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    Map<String, Object> normalize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(args));
    }

    String hash(Map<String, Object> args) {
        Map<String, Object> effectiveArgs = args == null ? Map.of() : args;
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(effectiveArgs);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            // CATCH: non-transactional fallback keeps policy evaluation available for non-JSON argument objects.
            return HexFormat.of().formatHex(String.valueOf(effectiveArgs).getBytes(StandardCharsets.UTF_8));
        }
    }
}
