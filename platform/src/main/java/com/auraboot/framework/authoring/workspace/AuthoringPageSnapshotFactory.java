package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Locale;
import java.util.Set;

/** Builds immutable authoring bases from the current PageSchema resource. */
@Component
public class AuthoringPageSnapshotFactory {

    private static final Set<String> CONCRETE_PAGE_KINDS =
            Set.of("form", "list", "detail", "dashboard");

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
        put(snapshot, "ownershipScope", page.getOwnershipScope());
        put(snapshot, "ownershipRef", page.getOwnershipRef());
        snapshot.set("title", parseOrDefault(page.getTitle(), objectMapper.createObjectNode()));
        snapshot.set("layout", parseOrDefault(page.getLayout(), objectMapper.createObjectNode()));
        snapshot.set("blocks", parseOrDefault(page.getBlocks(), objectMapper.createArrayNode()));
        return normalizeForAuthoring(snapshot);
    }

    /**
     * Normalizes persisted flat PageSchema blocks into the recursive Studio document shape.
     * The concrete page-kind container is server-owned: Studio may edit its descendants but
     * cannot delete or relocate the page root. Already-recursive snapshots remain stable.
     */
    public ObjectNode normalizeForAuthoring(JsonNode source) {
        if (!(source != null && source.deepCopy() instanceof ObjectNode snapshot)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "authoring.page.snapshot-object-required");
        }
        String kind = snapshot.path("kind").asText("");
        if (!CONCRETE_PAGE_KINDS.contains(kind)) {
            return snapshot;
        }

        ArrayNode blocks = requireBlocks(snapshot);
        if (blocks.size() == 1
                && blocks.get(0) instanceof ObjectNode existingRoot
                && kind.equals(existingRoot.path("blockType").asText())) {
            if (!existingRoot.path("id").isTextual()
                    || existingRoot.path("id").asText().isBlank()) {
                existingRoot.put("id", uniqueRootId(snapshot, blocks));
            }
            requireBlocks(existingRoot);
            return snapshot;
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", uniqueRootId(snapshot, blocks));
        root.put("blockType", kind);
        if (snapshot.has("title")) {
            root.set("title", snapshot.get("title").deepCopy());
        }
        if (snapshot.path("modelCode").isTextual()
                && !snapshot.path("modelCode").asText().isBlank()) {
            root.putObject("dataSource").put("model", snapshot.path("modelCode").asText());
        }
        if (snapshot.has("layout")) {
            root.set("layout", snapshot.get("layout").deepCopy());
        }
        root.set("blocks", blocks.deepCopy());
        snapshot.putArray("blocks").add(root);
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
        if ("TENANT".equals(snapshot.path("ownershipScope").asText())) {
            return ResourceScope.CURRENT_PAGE;
        }
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

    private ArrayNode requireBlocks(ObjectNode parent) {
        JsonNode blocks = parent.get("blocks");
        if (blocks == null || blocks.isNull() || blocks.isMissingNode()) {
            return parent.putArray("blocks");
        }
        if (blocks instanceof ArrayNode array) {
            return array;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "authoring.page.blocks-array-required");
    }

    private String uniqueRootId(ObjectNode snapshot, ArrayNode blocks) {
        String identity = snapshot.path("pageKey").asText(snapshot.path("pid").asText("page"));
        String base = stablePart(snapshot.path("kind").asText("page")) + "_" + stablePart(identity);
        if (base.length() > 110) {
            base = base.substring(0, 110).replaceAll("_+$", "");
        }
        String candidate = base;
        int suffix = 2;
        while (containsId(blocks, candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private String stablePart(String value) {
        String normalized = value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "page" : normalized;
    }

    private boolean containsId(JsonNode node, String id) {
        if (node instanceof ObjectNode object && id.equals(object.path("id").asText(null))) {
            return true;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (containsId(child, id)) {
                    return true;
                }
            }
        }
        return false;
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
