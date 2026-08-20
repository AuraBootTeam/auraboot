package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Prevents presentation edits from disguising the intent of protected business actions. */
@Component
public class AuthoringProtectedSemanticValidator {

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final MetaModelService metaModelService;

    public AuthoringProtectedSemanticValidator(
            CommandDefinitionMapper commandDefinitionMapper,
            MetaModelService metaModelService) {
        this.commandDefinitionMapper = commandDefinitionMapper;
        this.metaModelService = metaModelService;
    }

    public boolean isValid(
            JsonNode snapshot,
            ObjectNode block,
            String propertyPath,
            JsonNode proposedValue) {
        if (proposedValue == null || proposedValue.isNull()) {
            return false;
        }
        if ("/field".equals(propertyPath)) {
            return validModelField(snapshot, proposedValue);
        }
        if ("/extension/authoringCopyLineage".equals(propertyPath)) {
            return validCopyLineage(snapshot, block, proposedValue);
        }
        if ("/props/command".equals(propertyPath) || "/props/commandCode".equals(propertyPath)) {
            return proposedValue.isTextual()
                    && !proposedValue.asText().isBlank()
                    && commandDefinitionMapper.findCurrentByCode(proposedValue.asText()) != null;
        }
        JsonNode props = block.path("props");
        String commandCode = props.path("command").asText("");
        if (commandCode.isBlank()) {
            commandCode = props.path("commandCode").asText("");
        }
        CommandDefinition command = commandCode.isBlank()
                ? null
                : commandDefinitionMapper.findCurrentByCode(commandCode);
        boolean destructive = isDestructive(
                commandCode, command == null ? "" : command.getCmdRiskLevel());
        if ("/props/variant".equals(propertyPath)) {
            return !destructive || "danger".equalsIgnoreCase(proposedValue.asText());
        }
        if ("/props/label".equals(propertyPath)) {
            return validLabel(commandCode, proposedValue);
        }
        return true;
    }

    private boolean validCopyLineage(JsonNode snapshot, ObjectNode copiedBlock, JsonNode value) {
        if (!value.isObject() || value.size() != 1) {
            return false;
        }
        String sourceBlockId = value.path("sourceBlockId").asText("");
        if (sourceBlockId.isBlank() || sourceBlockId.equals(copiedBlock.path("id").asText(""))) {
            return false;
        }
        JsonNode sourceBlock = findBlock(snapshot.path("blocks"), sourceBlockId);
        return sourceBlock != null
                && sourceBlock.path("blockType").asText("")
                    .equals(copiedBlock.path("blockType").asText(""));
    }

    private JsonNode findBlock(JsonNode node, String blockId) {
        if (node.isObject() && blockId.equals(node.path("id").asText(null))) {
            return node;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode found = findBlock(child, blockId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean validModelField(JsonNode snapshot, JsonNode proposedValue) {
        if (!proposedValue.isTextual() || proposedValue.asText().isBlank()) {
            return false;
        }
        String modelCode = snapshot.path("modelCode").asText("");
        if (modelCode.isBlank()) {
            return false;
        }
        String fieldCode = proposedValue.asText();
        return metaModelService.getModelFields(modelCode).stream()
                .anyMatch(field -> fieldCode.equals(field.getCode()));
    }

    private boolean validLabel(String commandCode, JsonNode proposedValue) {
        String text = flattenText(proposedValue).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        String requiredIntent = destructiveIntent(commandCode);
        return requiredIntent == null || containsIntent(text, requiredIntent);
    }

    private boolean isDestructive(String commandCode, String risk) {
        return destructiveIntent(commandCode) != null
                || "L4".equalsIgnoreCase(risk)
                || "L3".equalsIgnoreCase(risk);
    }

    private String destructiveIntent(String commandCode) {
        String code = commandCode == null ? "" : commandCode.toLowerCase(Locale.ROOT);
        if (code.contains("delete") || code.contains("remove")) {
            return "delete";
        }
        if (code.contains("cancel") || code.contains("void")) {
            return "cancel";
        }
        if (code.contains("rollback") || code.contains("restore")) {
            return "rollback";
        }
        if (code.contains("pay") || code.contains("payment")) {
            return "pay";
        }
        if (code.contains("export")) {
            return "export";
        }
        if (code.contains("publish")) {
            return "publish";
        }
        return null;
    }

    private boolean containsIntent(String text, String intent) {
        return switch (intent) {
            case "delete" -> containsAny(text, "delete", "remove", "删除", "移除");
            case "cancel" -> containsAny(text, "cancel", "void", "取消", "作废");
            case "rollback" -> containsAny(text, "rollback", "restore", "回滚", "恢复");
            case "pay" -> containsAny(text, "pay", "payment", "付款", "支付");
            case "export" -> containsAny(text, "export", "导出");
            case "publish" -> containsAny(text, "publish", "发布");
            default -> false;
        };
    }

    private boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String flattenText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            StringBuilder result = new StringBuilder();
            node.elements().forEachRemaining(value -> result.append(' ').append(value.asText("")));
            return result.toString();
        }
        return "";
    }
}
