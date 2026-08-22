package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Replays two dependency-independent partitions from one immutable ChangeItem history. */
@Component
public class AuthoringChangeSetSplitter {

    private static final String MOVE_PATH = "/blocks/@order";
    private static final String PAGE_KIND_PATH = "/$page/kind";

    private final AuthoringSnapshotTargetResolver targetResolver;
    private final AuthoringJsonObjectPatchApplier patchApplier;
    private final AuthoringStableBlockTreeEditor blockTreeEditor;

    public AuthoringChangeSetSplitter(
            AuthoringSnapshotTargetResolver targetResolver,
            AuthoringJsonObjectPatchApplier patchApplier,
            AuthoringStableBlockTreeEditor blockTreeEditor) {
        this.targetResolver = targetResolver;
        this.patchApplier = patchApplier;
        this.blockTreeEditor = blockTreeEditor;
    }

    public SplitPlan split(
            JsonNode currentSnapshot,
            List<ChangeItem> items,
            List<String> selectedItemPids) {
        if (items == null || items.size() < 2) {
            throw conflict("authoring.split.requires-two-items");
        }
        if (selectedItemPids == null || selectedItemPids.isEmpty()) {
            throw conflict("authoring.split.selection-required");
        }
        Set<String> selected = new HashSet<>(selectedItemPids);
        if (selected.size() != selectedItemPids.size()) {
            throw conflict("authoring.split.selection-duplicate");
        }

        List<ChangeItem> targetItems = new ArrayList<>();
        List<ChangeItem> sourceItems = new ArrayList<>();
        for (ChangeItem item : items) {
            if (selected.remove(item.pid())) {
                targetItems.add(item);
            } else {
                sourceItems.add(item);
            }
        }
        if (!selected.isEmpty()) {
            throw conflict("authoring.split.item-not-found");
        }
        if (sourceItems.isEmpty() || targetItems.isEmpty()) {
            throw conflict("authoring.split.partition-empty");
        }

        requireNoCrossPartitionDependency(sourceItems, targetItems);
        JsonNode baseSnapshot = reconstructBase(currentSnapshot, items);
        JsonNode sourceSnapshot = replay(baseSnapshot, sourceItems);
        JsonNode targetSnapshot = replay(baseSnapshot, targetItems);
        return new SplitPlan(
                sourceSnapshot,
                targetSnapshot,
                List.copyOf(sourceItems),
                List.copyOf(targetItems),
                dependencySnapshots(sourceItems),
                dependencySnapshots(targetItems));
    }

    private JsonNode reconstructBase(JsonNode currentSnapshot, List<ChangeItem> items) {
        JsonNode snapshot = currentSnapshot.deepCopy();
        for (int index = items.size() - 1; index >= 0; index--) {
            snapshot = applyInverse(snapshot, items.get(index));
        }
        return snapshot;
    }

    private JsonNode replay(JsonNode baseSnapshot, List<ChangeItem> items) {
        JsonNode snapshot = baseSnapshot.deepCopy();
        for (ChangeItem item : items) {
            snapshot = applyForward(snapshot, item);
        }
        return snapshot;
    }

    private JsonNode applyForward(JsonNode snapshot, ChangeItem item) {
        if (PAGE_KIND_PATH.equals(item.propertyPath())) {
            AuthoringStableBlockTreeEditor.StructureResult result = blockTreeEditor.switchPageKind(
                    snapshot, item.newValue().asText());
            requireSame(result.previousValue(), item.oldValue());
            return result.snapshot();
        }
        if ("MOVE".equals(item.operation())) {
            AuthoringStableBlockTreeEditor.MoveResult result = blockTreeEditor.moveBefore(
                    snapshot, item.blockId(), beforeBlockId(item.newValue()));
            requireSame(result.previousValue(), item.oldValue());
            return result.snapshot();
        }
        PatchOperation operation = supportedOperation(item.operation());
        AuthoringSnapshotTargetResolver.DraftTarget target = targetResolver.resolve(
                snapshot, item.blockId());
        JsonNode previous = patchApplier.apply(
                target.block(), item.propertyPath(), operation, item.newValue());
        requireSame(previous, item.oldValue());
        return target.snapshot();
    }

    private JsonNode applyInverse(JsonNode snapshot, ChangeItem item) {
        if (PAGE_KIND_PATH.equals(item.propertyPath())) {
            AuthoringStableBlockTreeEditor.StructureResult result = blockTreeEditor.switchPageKind(
                    snapshot, item.oldValue().asText());
            requireSame(result.previousValue(), item.newValue());
            return result.snapshot();
        }
        if ("MOVE".equals(item.operation())) {
            AuthoringStableBlockTreeEditor.MoveResult result = blockTreeEditor.moveBefore(
                    snapshot, item.blockId(), beforeBlockId(item.oldValue()));
            requireSame(result.previousValue(), item.newValue());
            return result.snapshot();
        }
        AuthoringSnapshotTargetResolver.DraftTarget target = targetResolver.resolve(
                snapshot, item.blockId());
        PatchOperation inverse = switch (supportedOperation(item.operation())) {
            case ADD -> PatchOperation.REMOVE;
            case REPLACE -> PatchOperation.REPLACE;
            case REMOVE -> PatchOperation.ADD;
            default -> throw conflict("authoring.split.operation-unsupported");
        };
        JsonNode previous = patchApplier.apply(
                target.block(), item.propertyPath(), inverse, item.oldValue());
        requireSame(previous, item.newValue());
        return target.snapshot();
    }

    private PatchOperation supportedOperation(String value) {
        PatchOperation operation;
        try {
            operation = PatchOperation.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw conflict("authoring.split.operation-unsupported");
        }
        if (operation == PatchOperation.COPY || operation == PatchOperation.MOVE) {
            throw conflict("authoring.split.operation-unsupported");
        }
        return operation;
    }

    private void requireNoCrossPartitionDependency(
            List<ChangeItem> sourceItems,
            List<ChangeItem> targetItems) {
        for (ChangeItem source : sourceItems) {
            for (ChangeItem target : targetItems) {
                if (dependsOnSameState(source, target)) {
                    throw conflict("authoring.split.dependency-crosses-partition");
                }
            }
        }
    }

    private boolean dependsOnSameState(ChangeItem left, ChangeItem right) {
        if ("MOVE".equals(left.operation()) && "MOVE".equals(right.operation())) {
            return true;
        }
        return left.blockId().equals(right.blockId())
                && pathsOverlap(left.propertyPath(), right.propertyPath());
    }

    private boolean pathsOverlap(String left, String right) {
        if (MOVE_PATH.equals(left) || MOVE_PATH.equals(right)) {
            return false;
        }
        return left.equals(right)
                || left.startsWith(right + "/")
                || right.startsWith(left + "/");
    }

    private Map<String, JsonNode> dependencySnapshots(List<ChangeItem> items) {
        Map<String, JsonNode> snapshots = new LinkedHashMap<>();
        List<ChangeItem> preceding = new ArrayList<>();
        for (ChangeItem item : items) {
            ArrayNode dependencies = JsonNodeFactory.instance.arrayNode();
            for (ChangeItem candidate : preceding) {
                if (dependsOnSameState(candidate, item)) {
                    dependencies.add(candidate.pid());
                }
            }
            snapshots.put(item.pid(), dependencies);
            preceding.add(item);
        }
        return snapshots;
    }

    private String beforeBlockId(JsonNode value) {
        if (!(value instanceof ObjectNode object) || !object.has("beforeBlockId")) {
            throw conflict("authoring.split.move-value-invalid");
        }
        JsonNode before = object.get("beforeBlockId");
        return before == null || before.isNull() ? null : before.asText();
    }

    private void requireSame(JsonNode actual, JsonNode expected) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw conflict("authoring.split.dependency-crosses-partition");
        }
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }

    public record ChangeItem(
            long id,
            String pid,
            String blockId,
            String propertyPath,
            String operation,
            JsonNode oldValue,
            JsonNode newValue,
            JsonNode effectTags,
            String riskLevel,
            String route,
            String publishPolicy,
            String reversibility,
            String manifestChecksum,
            long baseRevision,
            long resultRevision,
            long actorUserId,
            Instant createdAt,
            Long sourceChangeItemId,
            String sourceChangeItemPid,
            JsonNode dependencySnapshot) {
    }

    public record SplitPlan(
            JsonNode sourceSnapshot,
            JsonNode targetSnapshot,
            List<ChangeItem> sourceItems,
            List<ChangeItem> targetItems,
            Map<String, JsonNode> sourceDependencySnapshots,
            Map<String, JsonNode> targetDependencySnapshots) {
    }
}
