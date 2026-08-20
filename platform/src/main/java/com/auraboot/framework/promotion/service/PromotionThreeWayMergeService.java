package com.auraboot.framework.promotion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Stable-ID three-way merge used by governed promotion REBASE decisions. */
@Component
public class PromotionThreeWayMergeService {

    private final ObjectMapper objectMapper;

    public PromotionThreeWayMergeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode merge(ObjectNode base, ObjectNode incoming, ObjectNode local) {
        JsonNode merged = mergeNode("", base, incoming, local, new LinkedHashSet<>());
        if (merged instanceof ObjectNode object) {
            return object;
        }
        throw conflict("promotion.drift.rebase-root-conflict");
    }

    private JsonNode mergeNode(
            String path,
            JsonNode base,
            JsonNode incoming,
            JsonNode local,
            Set<String> conflicts) {
        if (same(local, base)) {
            return copy(incoming);
        }
        if (same(incoming, base) || same(incoming, local)) {
            return copy(local);
        }
        if (base != null && incoming != null && local != null
                && base.isObject() && incoming.isObject() && local.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Set<String> fields = new LinkedHashSet<>();
            base.fieldNames().forEachRemaining(fields::add);
            incoming.fieldNames().forEachRemaining(fields::add);
            local.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode child = mergeNode(
                        path + "/" + field,
                        base.get(field), incoming.get(field), local.get(field), conflicts);
                if (child != null && !child.isMissingNode()) {
                    result.set(field, child);
                }
            }
            reject(conflicts);
            return result;
        }
        if (isStableIdArray(base) && isStableIdArray(incoming) && isStableIdArray(local)) {
            return mergeStableIdArray(path, (ArrayNode) base, (ArrayNode) incoming,
                    (ArrayNode) local, conflicts);
        }
        conflicts.add(path.isBlank() ? "/" : path);
        reject(conflicts);
        return null;
    }

    private ArrayNode mergeStableIdArray(
            String path,
            ArrayNode base,
            ArrayNode incoming,
            ArrayNode local,
            Set<String> conflicts) {
        ObjectNode baseById = byId(base);
        ObjectNode incomingById = byId(incoming);
        ObjectNode localById = byId(local);
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> orderedIds = new LinkedHashSet<>();
        local.forEach(node -> orderedIds.add(node.path("id").asText()));
        incoming.forEach(node -> orderedIds.add(node.path("id").asText()));
        for (String id : orderedIds) {
            JsonNode child = mergeNode(
                    path + "/@" + id,
                    baseById.get(id), incomingById.get(id), localById.get(id), conflicts);
            if (child != null && !child.isMissingNode()) {
                result.add(child);
            }
        }
        reject(conflicts);
        return result;
    }

    private ObjectNode byId(ArrayNode array) {
        ObjectNode result = objectMapper.createObjectNode();
        array.forEach(node -> result.set(node.path("id").asText(), node));
        return result;
    }

    private boolean isStableIdArray(JsonNode node) {
        if (!(node instanceof ArrayNode array)) {
            return false;
        }
        if (array.isEmpty()) {
            return true;
        }
        Iterator<JsonNode> elements = array.elements();
        while (elements.hasNext()) {
            JsonNode element = elements.next();
            if (!element.isObject() || !element.path("id").isTextual()
                    || element.path("id").asText().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean same(JsonNode first, JsonNode second) {
        return first == null ? second == null : first.equals(second);
    }

    private JsonNode copy(JsonNode node) {
        return node == null ? objectMapper.missingNode() : node.deepCopy();
    }

    private void reject(Set<String> conflicts) {
        if (!conflicts.isEmpty()) {
            throw conflict("promotion.drift.rebase-conflict:" + String.join(",", conflicts));
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }
}
