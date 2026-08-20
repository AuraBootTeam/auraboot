package com.auraboot.framework.promotion.diff;

import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
        if (src == null && tgt == null) {
            return;
        }
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
        Set<String> keys = new TreeSet<>();
        src.fieldNames().forEachRemaining(keys::add);
        tgt.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            compareNodes(childPath, src.get(key), tgt.get(key), out);
        }
    }

    private void compareArrays(String path, JsonNode src, JsonNode tgt, List<SemanticDiffEntry> out) {
        if (hasStableObjectIds(src) && hasStableObjectIds(tgt)) {
            compareStableIdArrays(path, src, tgt, out);
            return;
        }

        // Arrays without stable object ids keep positional semantics.
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

    /**
     * Compare block-like arrays by stable {@code id}. Reordering emits MOVE instead of a cascade
     * of index-aligned MODIFY/DELETE/ADD entries, so reviewers see the user's semantic action.
     */
    private void compareStableIdArrays(String path, JsonNode src, JsonNode tgt, List<SemanticDiffEntry> out) {
        Map<String, JsonNode> sourceById = indexByStableId(src);
        Map<String, JsonNode> targetById = indexByStableId(tgt);
        Map<String, Integer> sourcePositions = positionsByStableId(src);
        Map<String, Integer> targetPositions = positionsByStableId(tgt);

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(sourceById.keySet());
        allIds.addAll(targetById.keySet());

        for (String id : allIds) {
            JsonNode source = sourceById.get(id);
            JsonNode target = targetById.get(id);
            String itemPath = path + "[" + id + "]";

            if (source == null) {
                out.add(new SemanticDiffEntry(itemPath, SemanticDiffEntry.Op.ADD, null, materialize(target)));
                continue;
            }
            if (target == null) {
                out.add(new SemanticDiffEntry(itemPath, SemanticDiffEntry.Op.DELETE, materialize(source), null));
                continue;
            }

            Integer sourceIndex = sourcePositions.get(id);
            Integer targetIndex = targetPositions.get(id);
            if (!sourceIndex.equals(targetIndex)) {
                out.add(new SemanticDiffEntry(itemPath, SemanticDiffEntry.Op.MOVE, sourceIndex, targetIndex));
            }
            compareNodes(itemPath, source, target, out);
        }
    }

    private boolean hasStableObjectIds(JsonNode array) {
        if (array == null || !array.isArray()) {
            return false;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : array) {
            JsonNode id = item != null && item.isObject() ? item.get("id") : null;
            if (id == null || !id.isTextual() || id.asText().isBlank() || !ids.add(id.asText())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, JsonNode> indexByStableId(JsonNode array) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode item : array) {
            result.put(item.get("id").asText(), item);
        }
        return result;
    }

    private Map<String, Integer> positionsByStableId(JsonNode array) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < array.size(); index++) {
            result.put(array.get(index).get("id").asText(), index);
        }
        return result;
    }

    private JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON for diff: {}", e.getMessage());
            return null;
        }
    }

    /** Convert a JsonNode into a comparable Java value ({@link Map}, {@link List}, primitive). */
    private Object materialize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
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
