package com.auraboot.framework.meta.template.generator;

import com.auraboot.framework.meta.template.dto.DocumentConfig;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generates standard commands for DOCUMENT-type models based on documentConfig.
 *
 * Generated commands:
 * - CREATE header (auto-generate code + DRAFT status)
 * - UPDATE header (precondition: DRAFT only)
 * - DELETE header (precondition: DRAFT, cascade delete lines)
 * - ADD_LINE (computed amount + AGGREGATE SUM)
 * - DELETE_LINE (AGGREGATE SUM)
 * - State transitions (per stateMachine template)
 */
@Slf4j
@Component
public class DocumentCommandGenerator {

    /**
     * Generate standard commands for a DOCUMENT model.
     *
     * @param model  The DOCUMENT model definition
     * @param config Parsed documentConfig
     * @return List of command DTOs ready for import
     */
    public List<CommandDefinitionDTO> generateCommands(ModelDefinitionDTO model, DocumentConfig config) {
        List<CommandDefinitionDTO> commands = new ArrayList<>();
        String modelCode = model.getCode();
        String ns = extractNamespace(modelCode);
        String shortName = extractShortName(modelCode);

        log.info("Generating document commands for model={}, ns={}, short={}", modelCode, ns, shortName);

        // Header commands
        commands.add(buildCreateHeader(modelCode, ns, shortName, config));
        commands.add(buildUpdateHeader(modelCode, ns, shortName, config));
        commands.add(buildDeleteHeader(modelCode, ns, shortName, config));

        // Line commands
        if (config.hasLineModel()) {
            String lineShort = extractShortName(config.getLineModel());
            commands.add(buildAddLine(modelCode, ns, lineShort, config));
            commands.add(buildDeleteLine(modelCode, ns, lineShort, config));
        }

        // State transition commands
        commands.addAll(buildStateTransitions(modelCode, ns, shortName, config));

        log.info("Generated {} commands for model={}", commands.size(), modelCode);
        return commands;
    }

    // ==================== Header Commands ====================

    private CommandDefinitionDTO buildCreateHeader(String modelCode, String ns, String shortName, DocumentConfig config) {
        Map<String, Map<String, Object>> autoSetFields = new LinkedHashMap<>();
        if (config.getCodeField() != null && config.getCodePattern() != null) {
            autoSetFields.put(config.getCodeField(), Map.of(
                    "strategy", "auto_generate",
                    "pattern", config.getCodePattern()
            ));
        }
        autoSetFields.put(config.getStatusField(), Map.of(
                "strategy", "fixed_value",
                "value", "draft"
        ));

        return CommandDefinitionDTO.builder()
                .code(ns + ":create_" + shortName)
                .displayNameEn("Create " + humanize(shortName))
                .displayNameZhCN("新建" + humanize(shortName))
                .description("Create a new " + modelCode + " with auto-generated code and DRAFT status")
                .type("create")
                .modelCode(modelCode)
                .autoSetFields(autoSetFields)
                .build();
    }

    private CommandDefinitionDTO buildUpdateHeader(String modelCode, String ns, String shortName, DocumentConfig config) {
        return CommandDefinitionDTO.builder()
                .code(ns + ":update_" + shortName)
                .displayNameEn("Update " + humanize(shortName))
                .displayNameZhCN("编辑" + humanize(shortName))
                .description("Update " + modelCode + " (DRAFT only)")
                .type("update")
                .modelCode(modelCode)
                .preconditions(List.of(Map.of(
                        "type", "field_value",
                        "field", config.getStatusField(),
                        "operator", "IN",
                        "value", List.of("draft"),
                        "message:en", "Only DRAFT documents can be edited",
                        "message:zh-CN", "仅草稿状态可编辑"
                )))
                .build();
    }

    private CommandDefinitionDTO buildDeleteHeader(String modelCode, String ns, String shortName, DocumentConfig config) {
        CommandDefinitionDTO.CommandDefinitionDTOBuilder builder = CommandDefinitionDTO.builder()
                .code(ns + ":delete_" + shortName)
                .displayNameEn("Delete " + humanize(shortName))
                .displayNameZhCN("删除" + humanize(shortName))
                .description("Delete " + modelCode + " (DRAFT only, cascade deletes lines)")
                .type("delete")
                .modelCode(modelCode)
                .preconditions(List.of(Map.of(
                        "type", "field_value",
                        "field", config.getStatusField(),
                        "operator", "IN",
                        "value", List.of("draft"),
                        "message:en", "Only DRAFT documents can be deleted",
                        "message:zh-CN", "仅草稿状态可删除"
                )))
                .extension(Map.of(
                        "confirmMessage:en", "Delete this document?",
                        "confirmMessage:zh-CN", "确认删除此单据？"
                ));

        // Cascade delete lines
        if (config.hasLineModel()) {
            builder.cascadeDelete(List.of(
                    CommandDefinitionDTO.CascadeDeleteConfig.builder()
                            .childModel(config.getLineModel())
                            .parentField(config.getLineForeignKey())
                            .build()
            ));
        }

        return builder.build();
    }

    // ==================== Line Commands ====================

    private CommandDefinitionDTO buildAddLine(String modelCode, String ns, String lineShort, DocumentConfig config) {
        // Computed fields
        Map<String, String> computed = null;
        if (config.hasComputedAmount()) {
            computed = Map.of(config.getLineAmountField(),
                    config.getLineQtyField() + " * " + config.getLinePriceField());
        }

        // Aggregate side effects
        List<CommandDefinitionDTO.SideEffectConfig> sideEffects = buildAggregateSideEffects(modelCode, config);

        return CommandDefinitionDTO.builder()
                .code(ns + ":add_" + lineShort)
                .displayNameEn("Add Line")
                .displayNameZhCN("添加明细")
                .description("Add a line item to " + modelCode)
                .type("create")
                .modelCode(config.getLineModel())
                .computedFields(computed)
                .sideEffects(sideEffects.isEmpty() ? null : sideEffects)
                .build();
    }

    private CommandDefinitionDTO buildDeleteLine(String modelCode, String ns, String lineShort, DocumentConfig config) {
        List<CommandDefinitionDTO.SideEffectConfig> sideEffects = buildAggregateSideEffects(modelCode, config);

        return CommandDefinitionDTO.builder()
                .code(ns + ":delete_" + lineShort)
                .displayNameEn("Delete Line")
                .displayNameZhCN("删除明细")
                .description("Delete a line item from " + modelCode)
                .type("delete")
                .modelCode(config.getLineModel())
                .sideEffects(sideEffects.isEmpty() ? null : sideEffects)
                .extension(Map.of(
                        "confirmMessage:en", "Delete this line?",
                        "confirmMessage:zh-CN", "确认删除此明细？"
                ))
                .build();
    }

    private List<CommandDefinitionDTO.SideEffectConfig> buildAggregateSideEffects(String headerModel, DocumentConfig config) {
        if (config.getTotalFields() == null || config.getTotalFields().isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> actions = new ArrayList<>();
        for (DocumentConfig.TotalFieldMapping tf : config.getTotalFields()) {
            actions.add(Map.of(
                    "type", "aggregate",
                    "function", "sum",
                    "targetModel", headerModel,
                    "childModel", config.getLineModel(),
                    "childField", tf.getChildField(),
                    "parentField", tf.getParentField(),
                    "parentFk", config.getLineForeignKey()
            ));
        }

        return List.of(CommandDefinitionDTO.SideEffectConfig.builder()
                .condition("true")
                .actions(actions)
                .build());
    }

    // ==================== State Transition Commands ====================

    private List<CommandDefinitionDTO> buildStateTransitions(String modelCode, String ns, String shortName, DocumentConfig config) {
        String sm = config.getEffectiveStateMachine();
        String sf = config.getStatusField();

        return switch (sm) {
            case "simple" -> List.of(
                    buildTransition(modelCode, ns, shortName, sf, "confirm", "确认",
                            List.of("draft"), "confirmed", config)
            );
            case "standard" -> List.of(
                    buildTransition(modelCode, ns, shortName, sf, "submit", "提交",
                            List.of("draft"), "submitted", config),
                    buildTransition(modelCode, ns, shortName, sf, "approve", "审批通过",
                            List.of("submitted"), "approved", config),
                    buildTransition(modelCode, ns, shortName, sf, "start", "开始执行",
                            List.of("approved"), "in_progress", config),
                    buildTransition(modelCode, ns, shortName, sf, "complete", "完成",
                            List.of("in_progress"), "completed", config)
            );
            case "full" -> List.of(
                    buildTransition(modelCode, ns, shortName, sf, "submit", "提交",
                            List.of("draft"), "submitted", config),
                    buildTransition(modelCode, ns, shortName, sf, "approve", "审批通过",
                            List.of("submitted"), "approved", config),
                    buildTransition(modelCode, ns, shortName, sf, "reject", "驳回",
                            List.of("submitted"), "draft", config),
                    buildTransition(modelCode, ns, shortName, sf, "start", "开始执行",
                            List.of("approved"), "in_progress", config),
                    buildTransition(modelCode, ns, shortName, sf, "complete", "完成",
                            List.of("in_progress"), "completed", config),
                    buildTransition(modelCode, ns, shortName, sf, "cancel", "取消",
                            List.of("draft", "submitted", "approved"), "cancelled", config)
            );
            default -> {
                log.warn("Unknown stateMachine template: {}, using STANDARD", sm);
                yield buildStateTransitions(modelCode, ns, shortName,
                        new DocumentConfig() {{ setStateMachine("standard"); setStatusField(sf); }});
            }
        };
    }

    private CommandDefinitionDTO buildTransition(String modelCode, String ns, String shortName,
                                                  String stateField, String action, String zhLabel,
                                                  List<String> fromStates, String toState,
                                                  DocumentConfig config) {
        CommandDefinitionDTO.CommandDefinitionDTOBuilder builder = CommandDefinitionDTO.builder()
                .code(ns + ":" + action + "_" + shortName)
                .displayNameEn(capitalize(action) + " " + humanize(shortName))
                .displayNameZhCN(zhLabel)
                .description("Transition " + modelCode + " from " + fromStates + " to " + toState)
                .type("state_transition")
                .modelCode(modelCode)
                .stateField(stateField)
                .fromStates(fromStates)
                .toState(toState)
                .extension(Map.of(
                        "confirmMessage:en", capitalize(action) + " this document?",
                        "confirmMessage:zh-CN", "确认" + zhLabel + "？"
                ));

        // Submit requires at least one line item
        if ("submit".equals(action) && config.hasLineModel()) {
            builder.validation(CommandDefinitionDTO.ValidationConfig.builder()
                    .rules(List.of(Map.of(
                            "type", "has_children",
                            "childModel", config.getLineModel(),
                            "parentField", config.getLineForeignKey(),
                            "minCount", 1,
                            "message:en", "At least one line item is required before submission",
                            "message:zh-CN", "请至少添加一条明细后再提交"
                    )))
                    .build());
        }

        return builder.build();
    }

    // ==================== Helpers ====================

    /**
     * Extract namespace from model code.
     * "sl_sales_order" → "sl"
     * "pr_purchase_order" → "pr"
     */
    static String extractNamespace(String modelCode) {
        int idx = modelCode.indexOf('_');
        return idx > 0 ? modelCode.substring(0, idx) : modelCode;
    }

    /**
     * Extract short name from model code (everything after first underscore).
     * "sl_sales_order" → "sales_order"
     * "sl_sales_order_line" → "sales_order_line"
     */
    static String extractShortName(String modelCode) {
        int idx = modelCode.indexOf('_');
        return idx > 0 ? modelCode.substring(idx + 1) : modelCode;
    }

    private static String humanize(String name) {
        return name.replace('_', ' ');
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
