package com.auraboot.framework.dataquality.dbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code target/manifest.json} and optionally {@code catalog.json}
 * into typed value objects.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Streaming-friendly: accepts {@link InputStream} so callers can feed
 *       large files without loading the full byte array into heap.</li>
 *   <li>Unknown node resource types are accepted (not filtered here); callers
 *       may choose to skip non-{@code model} nodes during ingest.</li>
 *   <li>Catalog is optional: if the stream is {@code null} the returned
 *       catalog has an empty {@code nodes} map.</li>
 * </ul>
 *
 * @see DbtArtifact
 */
@Slf4j
@Component
public class DbtManifestParser {

    private final ObjectMapper objectMapper;

    public DbtManifestParser() {
        this.objectMapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // Manifest
    // -----------------------------------------------------------------------

    /**
     * Parses a {@code manifest.json} byte array.
     *
     * @param manifestBytes raw bytes of {@code manifest.json}
     * @return parsed manifest
     * @throws DbtParseException if the JSON is malformed or missing required fields
     */
    public DbtArtifact.DbtManifest parseManifest(byte[] manifestBytes) {
        try {
            JsonNode root = objectMapper.readTree(manifestBytes);
            return parseManifestRoot(root);
        } catch (IOException e) {
            throw new DbtParseException("Failed to parse manifest.json", e);
        }
    }

    /**
     * Parses a {@code manifest.json} input stream (streaming variant for large files).
     *
     * @param manifestStream input stream of {@code manifest.json}
     * @return parsed manifest
     * @throws DbtParseException if the JSON is malformed or missing required fields
     */
    public DbtArtifact.DbtManifest parseManifest(InputStream manifestStream) {
        try {
            JsonNode root = objectMapper.readTree(manifestStream);
            return parseManifestRoot(root);
        } catch (IOException e) {
            throw new DbtParseException("Failed to parse manifest.json from stream", e);
        }
    }

    private DbtArtifact.DbtManifest parseManifestRoot(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new DbtParseException("manifest.json must be a JSON object");
        }
        JsonNode nodesNode = root.path("nodes");
        if (nodesNode.isMissingNode()) {
            throw new DbtParseException("manifest.json missing required 'nodes' field");
        }
        if (!nodesNode.isObject()) {
            throw new DbtParseException("manifest.json 'nodes' must be an object");
        }

        Map<String, DbtArtifact.DbtNode> nodes = new HashMap<>();
        nodesNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            DbtArtifact.DbtNode node = parseNode(key, entry.getValue());
            nodes.put(key, node);
        });

        log.debug("Parsed manifest with {} nodes", nodes.size());
        return new DbtArtifact.DbtManifest(nodes);
    }

    private DbtArtifact.DbtNode parseNode(String key, JsonNode nodeJson) {
        String uniqueId = textOrDefault(nodeJson, "unique_id", key);
        String name = textOrDefault(nodeJson, "name", key);
        String resourceType = textOrDefault(nodeJson, "resource_type", "model");
        String database = textOrNull(nodeJson, "database");
        String schema = textOrNull(nodeJson, "schema");
        String alias = textOrNull(nodeJson, "alias");

        List<String> dependsOnNodes = new ArrayList<>();
        JsonNode dependsOn = nodeJson.path("depends_on");
        if (!dependsOn.isMissingNode() && dependsOn.isObject()) {
            JsonNode nodesArr = dependsOn.path("nodes");
            if (nodesArr.isArray()) {
                nodesArr.forEach(n -> dependsOnNodes.add(n.asText()));
            }
        }

        return new DbtArtifact.DbtNode(uniqueId, name, resourceType, database, schema, alias, dependsOnNodes);
    }

    // -----------------------------------------------------------------------
    // Catalog
    // -----------------------------------------------------------------------

    /**
     * Parses a {@code catalog.json} byte array.
     *
     * @param catalogBytes raw bytes of {@code catalog.json}; if null, returns empty catalog
     * @return parsed catalog (empty if input is null)
     * @throws DbtParseException if the JSON is malformed
     */
    public DbtArtifact.DbtCatalog parseCatalog(byte[] catalogBytes) {
        if (catalogBytes == null) {
            return new DbtArtifact.DbtCatalog(Map.of());
        }
        try {
            JsonNode root = objectMapper.readTree(catalogBytes);
            return parseCatalogRoot(root);
        } catch (IOException e) {
            throw new DbtParseException("Failed to parse catalog.json", e);
        }
    }

    /**
     * Parses a {@code catalog.json} input stream.
     *
     * @param catalogStream stream; if null, returns empty catalog
     * @return parsed catalog
     */
    public DbtArtifact.DbtCatalog parseCatalog(InputStream catalogStream) {
        if (catalogStream == null) {
            return new DbtArtifact.DbtCatalog(Map.of());
        }
        try {
            JsonNode root = objectMapper.readTree(catalogStream);
            return parseCatalogRoot(root);
        } catch (IOException e) {
            throw new DbtParseException("Failed to parse catalog.json from stream", e);
        }
    }

    private DbtArtifact.DbtCatalog parseCatalogRoot(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new DbtParseException("catalog.json must be a JSON object");
        }
        JsonNode nodesNode = root.path("nodes");
        if (nodesNode.isMissingNode() || !nodesNode.isObject()) {
            return new DbtArtifact.DbtCatalog(Map.of());
        }

        Map<String, DbtArtifact.DbtCatalogNode> nodes = new HashMap<>();
        nodesNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode nodeJson = entry.getValue();
            String uniqueId = textOrDefault(nodeJson, "unique_id", key);

            Map<String, String> columns = new HashMap<>();
            JsonNode columnsNode = nodeJson.path("columns");
            if (columnsNode.isObject()) {
                columnsNode.fields().forEachRemaining(col -> {
                    String colType = textOrDefault(col.getValue(), "type", "unknown");
                    // Column name comes from the key (or "name" field)
                    String colName = textOrDefault(col.getValue(), "name", col.getKey());
                    columns.put(colName, colType);
                });
            }
            nodes.put(key, new DbtArtifact.DbtCatalogNode(uniqueId, columns));
        });

        return new DbtArtifact.DbtCatalog(nodes);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode f = node.path(field);
        return (f.isTextual() && !f.asText().isEmpty()) ? f.asText() : defaultValue;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isTextual() ? f.asText() : null;
    }
}
