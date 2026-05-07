package com.auraboot.framework.promotion.diff;

import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Produces a field-level semantic diff between two {@link PageSchema} versions. Used by
 * promotion dry-run to surface what would change in the target env, and by the Diff Viewer UI
 * (task #11) for side-by-side highlight.
 *
 * <p>Diff covers the JSONB content fields {@code title}, {@code layout}, {@code blocks}.
 * Returns add / modify / delete entries with dotted paths.
 */
@Slf4j
@Service
public class PageSchemaDiffService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @return list of changes from source to target. Empty when content is equivalent.
     */
    public List<SemanticDiffEntry> diff(PageSchema source, PageSchema target) {
        List<SemanticDiffEntry> entries = new ArrayList<>();
        if (source == null && target == null) {
            return entries;
        }
        diffJsonField("title", source != null ? source.getTitle() : null,
                target != null ? target.getTitle() : null, entries);
        diffJsonField("layout", source != null ? source.getLayout() : null,
                target != null ? target.getLayout() : null, entries);
        diffJsonField("blocks", source != null ? source.getBlocks() : null,
                target != null ? target.getBlocks() : null, entries);
        return entries;
    }

    private void diffJsonField(String fieldName, String sourceJson, String targetJson, List<SemanticDiffEntry> out) {
        JsonNode src = parseOrNull(sourceJson);
        JsonNode tgt = parseOrNull(targetJson);
        if (src == null && tgt == null) return;
        compareNodes(fieldName, src, tgt, out);
    }

    private void compareNodes(String path, JsonNode src, JsonNode tgt, List<SemanticDiffEntry> out) {
        // Both sides null/missing — nothing to record.
        if ((src == null || src.isNull()) && (tgt == null || tgt.isNull())) {
            return;
        }
        // Source missing → ADD.
        if (src == null || src.isNull()) {
            out.add(new SemanticDiffEntry(path, SemanticDiffEntry.Op.ADD, null, materialize(tgt)));
            return;
        }
        // Target missing → DELETE.
        if (tgt == null || tgt.isNull()) {
            out.add(new SemanticDiffEntry(path, SemanticDiffEntry.Op.DELETE, materialize(src), null));
            return;
        }
        // Different types → record as MODIFY at the parent path.
        if (src.getNodeType() != tgt.getNodeType()) {
            out.add(new SemanticDiffEntry(path, SemanticDiffEntry.Op.MODIFY, materialize(src), materialize(tgt)));
            return;
        }
        if (src.isObject()) {
            compareObjects(path, src, tgt, out);
        } else if (src.isArray()) {
            compareArrays(path, src, tgt, out);
        } else if (!src.equals(tgt)) {
            out.add(new SemanticDiffEntry(path, SemanticDiffEntry.Op.MODIFY, materialize(src), materialize(tgt)));
        }
    }

    private void compareObjects(String path, JsonNode src, JsonNode tgt, List<SemanticDiffEntry> out) {
        Set<String> keys = new HashSet<>();
        src.fieldNames().forEachRemaining(keys::add);
        tgt.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            compareNodes(childPath, src.get(key), tgt.get(key), out);
        }
    }

    private void compareArrays(String path, JsonNode src, JsonNode tgt, List<SemanticDiffEntry> out) {
        // Naive index-aligned compare. Smarter heuristics (LCS, key-based matching) deferred.
        int srcSize = src.size();
        int tgtSize = tgt.size();
        int common = Math.min(srcSize, tgtSize);
        for (int i = 0; i < common; i++) {
            compareNodes(path + "[" + i + "]", src.get(i), tgt.get(i), out);
        }
        for (int i = common; i < srcSize; i++) {
            out.add(new SemanticDiffEntry(path + "[" + i + "]", SemanticDiffEntry.Op.DELETE,
                    materialize(src.get(i)), null));
        }
        for (int i = common; i < tgtSize; i++) {
            out.add(new SemanticDiffEntry(path + "[" + i + "]", SemanticDiffEntry.Op.ADD,
                    null, materialize(tgt.get(i))));
        }
    }

    private JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON for diff: {}", e.getMessage());
            return null;
        }
    }

    /** Convert a JsonNode into a comparable Java value ({@link Map}, {@link List}, primitive). */
    private Object materialize(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                map.put(e.getKey(), materialize(e.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                list.add(materialize(child));
            }
            return list;
        }
        return node.toString();
    }
}
