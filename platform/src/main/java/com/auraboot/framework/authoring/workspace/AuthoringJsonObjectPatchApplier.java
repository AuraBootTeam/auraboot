package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Applies the restricted object-only subset of JSON Patch accepted by contextual authoring. */
@Component
public class AuthoringJsonObjectPatchApplier {

    private static final Set<String> FORBIDDEN_SEGMENTS =
            Set.of("id", "blockType", "type", "__proto__", "prototype", "constructor");

    public JsonNode apply(
            ObjectNode block,
            String propertyPath,
            PatchOperation operation,
            JsonNode savedValue) {
        if (operation == PatchOperation.MOVE || operation == PatchOperation.COPY) {
            throw invalid("authoring.patch.operation-not-supported");
        }
        List<String> segments = parsePointer(propertyPath);
        ObjectNode parent = resolveParent(block, segments, operation);
        return applyLeaf(parent, segments.get(segments.size() - 1), operation, savedValue);
    }

    private ObjectNode resolveParent(
            ObjectNode block,
            List<String> segments,
            PatchOperation operation) {
        ObjectNode parent = block;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            JsonNode child = parent.get(segment);
            if (child == null && operation == PatchOperation.ADD) {
                child = parent.putObject(segment);
            }
            if (!(child instanceof ObjectNode objectChild)) {
                throw invalid("authoring.patch.parent-not-object");
            }
            parent = objectChild;
        }
        return parent;
    }

    private JsonNode applyLeaf(
            ObjectNode parent,
            String leaf,
            PatchOperation operation,
            JsonNode savedValue) {
        JsonNode prior = parent.get(leaf);
        requireValidTarget(operation, prior);
        JsonNode previous = prior == null ? null : prior.deepCopy();
        if (operation == PatchOperation.REMOVE) {
            parent.remove(leaf);
        } else {
            if (savedValue == null) {
                throw invalid("authoring.patch.value-required");
            }
            parent.set(leaf, savedValue);
        }
        return previous;
    }

    private void requireValidTarget(PatchOperation operation, JsonNode prior) {
        if (operation == PatchOperation.ADD && prior != null) {
            throw new ResponseStatusException(CONFLICT, "authoring.patch.add-target-exists");
        }
        if ((operation == PatchOperation.REPLACE || operation == PatchOperation.REMOVE)
                && prior == null) {
            throw new ResponseStatusException(CONFLICT, "authoring.patch.target-missing");
        }
    }

    private List<String> parsePointer(String propertyPath) {
        if (propertyPath == null || !propertyPath.startsWith("/") || propertyPath.length() < 2) {
            throw invalid("authoring.patch.pointer-invalid");
        }
        String[] raw = propertyPath.substring(1).split("/", -1);
        List<String> result = new ArrayList<>(raw.length);
        for (String part : raw) {
            String segment = part.replace("~1", "/").replace("~0", "~");
            if (segment.isBlank() || FORBIDDEN_SEGMENTS.contains(segment)) {
                throw invalid("authoring.patch.pointer-forbidden");
            }
            result.add(segment);
        }
        return result;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, reason);
    }
}
