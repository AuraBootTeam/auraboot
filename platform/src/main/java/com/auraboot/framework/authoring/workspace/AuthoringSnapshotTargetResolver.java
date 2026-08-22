package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Resolves a stable block identity inside an isolated draft snapshot. */
@Component
public class AuthoringSnapshotTargetResolver {

    public DraftTarget resolve(JsonNode sourceSnapshot, String blockId) {
        if (sourceSnapshot == null || !sourceSnapshot.isObject()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "authoring.snapshot.invalid");
        }
        ObjectNode snapshot = sourceSnapshot.deepCopy();
        ObjectNode block = findBlock(snapshot, blockId);
        if (block == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.block.not-found");
        }
        return new DraftTarget(snapshot, block, blockType(block));
    }

    private ObjectNode findBlock(JsonNode node, String blockId) {
        if (node instanceof ObjectNode object) {
            ObjectNode direct = findInObject(object, blockId);
            if (direct != null) {
                return direct;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                ObjectNode found = findBlock(child, blockId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ObjectNode findInObject(ObjectNode object, String blockId) {
        if (blockId != null && blockId.equals(object.path("id").asText(null))) {
            return object;
        }
        for (JsonNode child : object) {
            ObjectNode found = findBlock(child, blockId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String blockType(ObjectNode block) {
        String type = firstText(block, "blockType", "type", "kind");
        if (type == null) {
            return "";
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "listblock", "tableblock" -> "table";
            case "fieldblock" -> "field";
            case "actionblock" -> "action";
            default -> normalized;
        };
    }

    private String firstText(ObjectNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    public record DraftTarget(ObjectNode snapshot, ObjectNode block, String blockType) {
    }
}
