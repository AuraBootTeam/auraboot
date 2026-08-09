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

/** Applies server-validated stable-ID structure operations to an isolated PageSchema snapshot. */
@Component
public class AuthoringStableBlockTreeEditor {

    private final CoreAuthoringStructurePolicy structurePolicy;

    public AuthoringStableBlockTreeEditor(CoreAuthoringStructurePolicy structurePolicy) {
        this.structurePolicy = structurePolicy;
    }

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

    public StructureResult createBlock(
            JsonNode sourceSnapshot,
            String blockId,
            String blockType,
            String parentBlockId,
            String beforeBlockId) {
        ObjectNode snapshot = validSnapshot(sourceSnapshot);
        requireIdentifier(blockId, "authoring.structure.block-id-required");
        requireIdentifier(blockType, "authoring.structure.block-type-required");
        if (!occurrences(snapshot, blockId).isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "authoring.structure.block-id-exists");
        }

        ArrayNode destination;
        String parentBlockType = null;
        if (parentBlockId == null) {
            if (!structurePolicy.allowsRoot(snapshot, blockType)) {
                throw invalid("authoring.structure.root-containment-denied");
            }
            destination = ensureBlocks(snapshot);
            if (!"composite".equals(snapshot.path("kind").asText("composite"))
                    && !destination.isEmpty()) {
                throw invalid("authoring.structure.root-already-present");
            }
        } else {
            Occurrence parent = uniqueOccurrence(snapshot, parentBlockId);
            parentBlockType = parent.block().path("blockType").asText(null);
            if (!structurePolicy.allowsChild(parentBlockType, blockType)) {
                throw invalid("authoring.structure.containment-denied");
            }
            destination = ensureBlocks(parent.block());
        }
        int targetIndex = insertionIndex(destination, beforeBlockId);
        ObjectNode block = JsonNodeFactory.instance.objectNode();
        block.put("id", blockId);
        block.put("blockType", blockType);
        if (structurePolicy.isContainer(blockType)) {
            block.putArray("blocks");
        }
        destination.insert(targetIndex, block);
        return new StructureResult(
                snapshot,
                blockType,
                null,
                structureValue(blockId, blockType, parentBlockId, parentBlockType, beforeBlockId, 0));
    }

    public StructureResult removeBlock(JsonNode sourceSnapshot, String blockId) {
        ObjectNode snapshot = validSnapshot(sourceSnapshot);
        requireIdentifier(blockId, "authoring.structure.block-id-required");
        Occurrence occurrence = uniqueOccurrence(snapshot, blockId);
        if (occurrence.parentBlockId() == null) {
            throw invalid("authoring.structure.root-delete-denied");
        }
        String beforeBlockId = siblingId(occurrence.siblings(), occurrence.index() + 1);
        int descendantCount = descendantCount(occurrence.block());
        JsonNode previous = structureValue(
                blockId,
                occurrence.block().path("blockType").asText(),
                occurrence.parentBlockId(),
                occurrence.parentBlockType(),
                beforeBlockId,
                descendantCount);
        occurrence.siblings().remove(occurrence.index());
        return new StructureResult(
                snapshot,
                occurrence.block().path("blockType").asText(),
                previous,
                null);
    }

    public StructureResult relocateBlock(
            JsonNode sourceSnapshot,
            String blockId,
            String targetParentBlockId,
            String beforeBlockId) {
        ObjectNode snapshot = validSnapshot(sourceSnapshot);
        requireIdentifier(blockId, "authoring.structure.block-id-required");
        requireIdentifier(targetParentBlockId, "authoring.structure.parent-id-required");
        Occurrence moving = uniqueOccurrence(snapshot, blockId);
        if (moving.parentBlockId() == null) {
            throw invalid("authoring.structure.root-relocate-denied");
        }
        if (targetParentBlockId.equals(moving.parentBlockId())) {
            throw invalid("authoring.structure.same-parent-use-reorder");
        }
        if (containsBlockId(moving.block(), targetParentBlockId)) {
            throw invalid("authoring.structure.cycle-denied");
        }
        Occurrence targetParent = uniqueOccurrence(snapshot, targetParentBlockId);
        String blockType = moving.block().path("blockType").asText();
        String targetParentType = targetParent.block().path("blockType").asText();
        if (!structurePolicy.allowsChild(targetParentType, blockType)) {
            throw invalid("authoring.structure.containment-denied");
        }
        ArrayNode destination = ensureBlocks(targetParent.block());
        int targetIndex = insertionIndex(destination, beforeBlockId);
        String previousBeforeBlockId = siblingId(moving.siblings(), moving.index() + 1);
        JsonNode previous = structureValue(
                blockId, blockType, moving.parentBlockId(), moving.parentBlockType(),
                previousBeforeBlockId, descendantCount(moving.block()));
        JsonNode moved = moving.siblings().remove(moving.index());
        destination.insert(targetIndex, moved);
        JsonNode saved = structureValue(
                blockId, blockType, targetParentBlockId, targetParentType,
                beforeBlockId, descendantCount(moved));
        return new StructureResult(snapshot, blockType, previous, saved);
    }

    private ObjectNode validSnapshot(JsonNode sourceSnapshot) {
        if (sourceSnapshot == null || !sourceSnapshot.isObject()) {
            throw invalid("authoring.snapshot.invalid");
        }
        return sourceSnapshot.deepCopy();
    }

    private void requireIdentifier(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw invalid(reason);
        }
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
            throw invalid("authoring.structure.identifier-invalid");
        }
    }

    private Occurrence uniqueOccurrence(ObjectNode snapshot, String blockId) {
        List<Occurrence> matches = occurrences(snapshot, blockId);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.block.not-found");
        }
        if (matches.size() != 1) {
            throw invalid("authoring.structure.duplicate-block-id");
        }
        return matches.get(0);
    }

    private ArrayNode ensureBlocks(ObjectNode parent) {
        JsonNode current = parent.get("blocks");
        if (current == null || current.isNull()) {
            return parent.putArray("blocks");
        }
        if (!(current instanceof ArrayNode array)) {
            throw invalid("authoring.structure.children-invalid");
        }
        return array;
    }

    private int insertionIndex(ArrayNode siblings, String beforeBlockId) {
        if (beforeBlockId == null) {
            return siblings.size();
        }
        int targetIndex = directSiblingIndex(siblings, beforeBlockId);
        if (targetIndex < 0) {
            throw invalid("authoring.structure.target-not-sibling");
        }
        return targetIndex;
    }

    private List<Occurrence> occurrences(JsonNode node, String blockId) {
        List<Occurrence> result = new ArrayList<>();
        collectOccurrences(node, blockId, null, null, result);
        return result;
    }

    private void collectOccurrences(
            JsonNode node,
            String blockId,
            String parentBlockId,
            String parentBlockType,
            List<Occurrence> result) {
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
                    result.add(new Occurrence(
                            childObject, blocks, index, parentBlockId, parentBlockType));
                }
                collectOccurrences(
                        childObject,
                        blockId,
                        childObject.path("id").asText(null),
                        childObject.path("blockType").asText(null),
                        result);
            }
        }
    }

    private boolean containsBlockId(JsonNode node, String blockId) {
        if (!(node instanceof ObjectNode object)) {
            return false;
        }
        if (blockId.equals(object.path("id").asText(null))) {
            return true;
        }
        JsonNode children = object.get("blocks");
        if (!(children instanceof ArrayNode blocks)) {
            return false;
        }
        for (JsonNode child : blocks) {
            if (containsBlockId(child, blockId)) {
                return true;
            }
        }
        return false;
    }

    private int descendantCount(JsonNode node) {
        JsonNode children = node.get("blocks");
        if (!(children instanceof ArrayNode blocks)) {
            return 0;
        }
        int count = blocks.size();
        for (JsonNode child : blocks) {
            count += descendantCount(child);
        }
        return count;
    }

    private ObjectNode structureValue(
            String blockId,
            String blockType,
            String parentBlockId,
            String parentBlockType,
            String beforeBlockId,
            int descendantCount) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("blockId", blockId);
        value.put("blockType", blockType);
        putNullable(value, "parentBlockId", parentBlockId);
        putNullable(value, "parentBlockType", parentBlockType);
        putNullable(value, "beforeBlockId", beforeBlockId);
        value.put("descendantCount", descendantCount);
        return value;
    }

    private void putNullable(ObjectNode node, String property, String value) {
        if (value == null) {
            node.putNull(property);
        } else {
            node.put(property, value);
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

    private record Occurrence(
            ObjectNode block,
            ArrayNode siblings,
            int index,
            String parentBlockId,
            String parentBlockType) {
    }

    public record MoveResult(
            ObjectNode snapshot,
            JsonNode previousValue,
            JsonNode savedValue) {
    }

    public record StructureResult(
            ObjectNode snapshot,
            String blockType,
            JsonNode previousValue,
            JsonNode savedValue) {
    }
}
