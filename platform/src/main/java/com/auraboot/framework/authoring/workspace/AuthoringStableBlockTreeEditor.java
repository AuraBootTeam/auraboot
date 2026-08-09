package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Applies stable-ID, same-parent reorder operations to an isolated PageSchema snapshot. */
@Component
public class AuthoringStableBlockTreeEditor {

    public MoveResult moveBefore(JsonNode sourceSnapshot, String blockId, String beforeBlockId) {
        if (sourceSnapshot == null || !sourceSnapshot.isObject()) {
            throw invalid("authoring.snapshot.invalid");
        }
        if (blockId == null || blockId.isBlank()) {
            throw invalid("authoring.structure.block-id-required");
        }
        if (blockId.equals(beforeBlockId)) {
            throw invalid("authoring.structure.target-self");
        }

        ObjectNode snapshot = sourceSnapshot.deepCopy();
        List<Occurrence> matches = occurrences(snapshot, blockId);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.block.not-found");
        }
        if (matches.size() != 1) {
            throw invalid("authoring.structure.duplicate-block-id");
        }

        Occurrence moving = matches.get(0);
        ArrayNode siblings = moving.siblings();
        int sourceIndex = moving.index();
        String previousBeforeBlockId = siblingId(siblings, sourceIndex + 1);
        if (sameTarget(previousBeforeBlockId, beforeBlockId)) {
            throw new ResponseStatusException(CONFLICT, "authoring.structure.no-op");
        }

        int targetIndex = beforeBlockId == null
                ? siblings.size()
                : directSiblingIndex(siblings, beforeBlockId);
        if (targetIndex < 0) {
            throw invalid("authoring.structure.target-not-sibling");
        }

        JsonNode moved = siblings.remove(sourceIndex);
        if (sourceIndex < targetIndex) {
            targetIndex--;
        }
        siblings.insert(targetIndex, moved);
        return new MoveResult(
                snapshot,
                orderValue(previousBeforeBlockId),
                orderValue(beforeBlockId));
    }

    private List<Occurrence> occurrences(JsonNode node, String blockId) {
        List<Occurrence> result = new ArrayList<>();
        collectOccurrences(node, blockId, result);
        return result;
    }

    private void collectOccurrences(JsonNode node, String blockId, List<Occurrence> result) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        JsonNode children = object.get("blocks");
        if (!(children instanceof ArrayNode blocks)) {
            return;
        }
        for (int index = 0; index < blocks.size(); index++) {
            JsonNode child = blocks.get(index);
            if (child instanceof ObjectNode childObject) {
                if (blockId.equals(childObject.path("id").asText(null))) {
                    result.add(new Occurrence(blocks, index));
                }
                collectOccurrences(childObject, blockId, result);
            }
        }
    }

    private int directSiblingIndex(ArrayNode siblings, String blockId) {
        int found = -1;
        for (int index = 0; index < siblings.size(); index++) {
            if (blockId.equals(siblings.get(index).path("id").asText(null))) {
                if (found >= 0) {
                    throw invalid("authoring.structure.duplicate-block-id");
                }
                found = index;
            }
        }
        return found;
    }

    private String siblingId(ArrayNode siblings, int index) {
        if (index < 0 || index >= siblings.size()) {
            return null;
        }
        JsonNode id = siblings.get(index).get("id");
        return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
    }

    private boolean sameTarget(String current, String requested) {
        return current == null ? requested == null : current.equals(requested);
    }

    private ObjectNode orderValue(String beforeBlockId) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        if (beforeBlockId == null) {
            value.putNull("beforeBlockId");
        } else {
            value.put("beforeBlockId", beforeBlockId);
        }
        return value;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, reason);
    }

    private record Occurrence(ArrayNode siblings, int index) {
    }

    public record MoveResult(
            ObjectNode snapshot,
            JsonNode previousValue,
            JsonNode savedValue) {
    }
}
