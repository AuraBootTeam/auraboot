package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the current server-owned draft without executing page business behavior. */
@Component
public class AuthoringDraftValidator {

    public static final String VALIDATOR_VERSION = "core-page-v1";
    private static final Set<String> DENSITIES = Set.of("normal", "compact", "comfortable");

    public ValidationResult validate(JsonNode snapshot, List<ChangeItem> activeItems) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, JsonNode> blocksById = new HashMap<>();
        if (snapshot == null || !snapshot.isObject()) {
            issues.add(issue("PAGE_SNAPSHOT_INVALID", null, null, "/",
                    "authoring.validation.page-snapshot-invalid"));
            return invalid(issues);
        }
        if (!snapshot.path("pid").isTextual() || snapshot.path("pid").asText().isBlank()) {
            issues.add(issue("PAGE_ID_REQUIRED", null, null, "/pid",
                    "authoring.validation.page-id-required"));
        }
        JsonNode blocks = snapshot.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            issues.add(issue("BLOCKS_ARRAY_REQUIRED", null, null, "/blocks",
                    "authoring.validation.blocks-array-required"));
        } else {
            collectBlocks(blocks, "/blocks", new HashSet<>(), blocksById, issues);
        }
        if (activeItems != null) {
            for (ChangeItem item : activeItems) {
                validateChangedProperty(item, blocksById, issues);
            }
        }
        return issues.isEmpty()
                ? new ValidationResult("VALID", List.of())
                : invalid(issues);
    }

    private void collectBlocks(
            JsonNode blocks,
            String path,
            Set<String> seenIds,
            Map<String, JsonNode> blocksById,
            List<ValidationIssue> issues) {
        for (int index = 0; index < blocks.size(); index++) {
            JsonNode block = blocks.get(index);
            String blockPath = path + "/" + index;
            if (!block.isObject()) {
                issues.add(issue("BLOCK_OBJECT_REQUIRED", null, null, blockPath,
                        "authoring.validation.block-object-required"));
                continue;
            }
            String blockId = text(block.get("id"));
            if (blockId == null) {
                issues.add(issue("BLOCK_ID_REQUIRED", null, null, blockPath + "/id",
                        "authoring.validation.block-id-required"));
            } else if (!seenIds.add(blockId)) {
                issues.add(issue("BLOCK_ID_DUPLICATE", null, blockId, blockPath + "/id",
                        "authoring.validation.block-id-duplicate"));
            } else {
                blocksById.put(blockId, block);
            }
            if (text(block.get("blockType")) == null) {
                issues.add(issue("BLOCK_TYPE_REQUIRED", null, blockId,
                        blockPath + "/blockType", "authoring.validation.block-type-required"));
            }
            JsonNode children = block.get("blocks");
            if (children != null && !children.isNull()) {
                if (children.isArray()) {
                    collectBlocks(children, blockPath + "/blocks", seenIds, blocksById, issues);
                } else {
                    issues.add(issue("CHILD_BLOCKS_ARRAY_REQUIRED", null, blockId,
                            blockPath + "/blocks",
                            "authoring.validation.child-blocks-array-required"));
                }
            }
        }
    }

    private void validateChangedProperty(
            ChangeItem item,
            Map<String, JsonNode> blocksById,
            List<ValidationIssue> issues) {
        if (item.propertyPath().startsWith("/$resource/")) {
            validateResourceChange(item, issues);
            return;
        }
        if ("MOVE".equals(item.operation()) || "REMOVE".equals(item.operation())) {
            return;
        }
        JsonNode block = blocksById.get(item.blockId());
        if (block == null) {
            issues.add(issue("CHANGE_TARGET_MISSING", item.pid(), item.blockId(),
                    item.propertyPath(), "authoring.validation.change-target-missing"));
            return;
        }
        JsonNode value = block.at(item.propertyPath());
        if (value.isMissingNode()) {
            issues.add(issue("CHANGED_PROPERTY_MISSING", item.pid(), item.blockId(),
                    item.propertyPath(), "authoring.validation.changed-property-missing"));
            return;
        }
        String code = invalidValueCode(item.propertyPath(), value);
        if (code != null) {
            issues.add(issue(code, item.pid(), item.blockId(), item.propertyPath(),
                    "authoring.validation." + code.toLowerCase().replace('_', '-')));
        }
    }

    private void validateResourceChange(ChangeItem item, List<ValidationIssue> issues) {
        if (!"ADD".equals(item.operation()) || item.newValue() == null || !item.newValue().isObject()) {
            issues.add(issue("RESOURCE_ADD_INVALID", item.pid(), item.blockId(),
                    item.propertyPath(), "authoring.validation.resource-add-invalid"));
        }
    }

    private String invalidValueCode(String propertyPath, JsonNode value) {
        return switch (propertyPath) {
            case "/props/density" -> value.isTextual() && DENSITIES.contains(value.asText())
                    ? null : "DENSITY_INVALID";
            case "/props/pageSize" -> value.isIntegralNumber()
                    && value.asInt() >= 1 && value.asInt() <= 1000
                    ? null : "PAGE_SIZE_INVALID";
            case "/layout/span" -> value.isIntegralNumber()
                    && value.asInt() >= 1 && value.asInt() <= 24
                    ? null : "LAYOUT_SPAN_INVALID";
            case "/props/defaultFilter" -> value.isObject() || value.isArray()
                    ? null : "DEFAULT_FILTER_INVALID";
            case "/props/defaultSort" -> value.isArray() ? null : "DEFAULT_SORT_INVALID";
            case "/dataSource" -> value.isObject()
                    && text(value.get("model")) != null
                    ? null : "DATA_SOURCE_MODEL_REQUIRED";
            default -> null;
        };
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText() : null;
    }

    private ValidationResult invalid(List<ValidationIssue> issues) {
        return new ValidationResult("INVALID", List.copyOf(issues));
    }

    private ValidationIssue issue(
            String code,
            String changeItemPid,
            String blockId,
            String propertyPath,
            String messageKey) {
        return new ValidationIssue(
                code, "ERROR", changeItemPid, blockId, propertyPath, messageKey);
    }

    public record ValidationResult(String status, List<ValidationIssue> issues) {
        public boolean valid() {
            return "VALID".equals(status);
        }

        public int errorCount() {
            return issues.size();
        }
    }

    public record ValidationIssue(
            String code,
            String severity,
            String changeItemPid,
            String blockId,
            String propertyPath,
            String messageKey) {
    }
}
