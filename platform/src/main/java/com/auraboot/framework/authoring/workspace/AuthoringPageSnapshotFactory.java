package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** Builds immutable authoring bases from the current PageSchema resource. */
@Component
public class AuthoringPageSnapshotFactory {

    private final ObjectMapper objectMapper;

    public AuthoringPageSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode create(PageSchema page) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("pid", page.getPid());
        put(snapshot, "pageKey", page.getPageKey());
        put(snapshot, "modelCode", page.getModelCode());
        put(snapshot, "name", page.getName());
        put(snapshot, "description", page.getDescription());
        put(snapshot, "kind", page.getKind());
        putInteger(snapshot, "schemaVersion", page.getSchemaVersion());
        put(snapshot, "profile", page.getProfile());
        putBoolean(snapshot, "isTemplate", page.getIsTemplate());
        put(snapshot, "pluginPid", page.getPluginPid());
        snapshot.set("title", parseOrDefault(page.getTitle(), objectMapper.createObjectNode()));
        snapshot.set("layout", parseOrDefault(page.getLayout(), objectMapper.createObjectNode()));
        snapshot.set("blocks", parseOrDefault(page.getBlocks(), objectMapper.createArrayNode()));
        return snapshot;
    }

    public String checksum(JsonNode value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonicalize(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to checksum authoring snapshot", e);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields);
            fields.forEach(field -> result.set(field, canonicalize(value.get(field))));
            return result;
        }
        if (value.isArray()) {
            var result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    public ResourceScope resourceScope(JsonNode snapshot) {
        if (snapshot.path("isTemplate").asBoolean(false)
                || !snapshot.path("pluginPid").asText("").isBlank()) {
            return ResourceScope.SHARED_PAGE;
        }
        return ResourceScope.CURRENT_PAGE;
    }

    public long baseVersion(PageSchema page) {
        if (page.getRowVersion() != null) {
            return page.getRowVersion();
        }
        return page.getVersion() == null ? 1L : page.getVersion().longValue();
    }

    public String title(PageSchema page) {
        return "Edit page " + page.getPid();
    }

    private JsonNode parseOrDefault(String value, JsonNode defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "authoring.page.invalid-json", e);
        }
    }

    private void put(ObjectNode target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private void putInteger(ObjectNode target, String field, Integer value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private void putBoolean(ObjectNode target, String field, Boolean value) {
        if (value != null) {
            target.put(field, value);
        }
    }
}
