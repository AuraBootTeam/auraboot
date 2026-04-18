package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates deterministic default blocks for a new PageSchema based on the
 * bound model's fields + capabilities. Rules per design §12.8.
 *
 * <p>Usage: invoked by {@link PageSchemaServiceImpl#create} when the incoming
 * {@code blocks} list is null/empty and a {@code modelCode} is bound. Produces
 * blocks as {@code Map<String,Object>} — compatible with the request DTO's
 * {@code List<Object>} blocks field and with JSONB serialization downstream.
 *
 * <p>Kind rules:
 * <ul>
 *   <li><b>list</b>: filters + toolbar + table</li>
 *   <li><b>detail</b>: optional actions toolbar + main detail-section + audit detail-section (collapsed)</li>
 *   <li><b>form</b>: single form-section + form-buttons</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PageSchemaDefaultBlockGenerator {

    private static final Set<String> PRIORITY_FIELDS = new LinkedHashSet<>(List.of(
        "name", "title", "code", "status", "createdAt", "created_at", "updatedAt", "updated_at"
    ));
    private static final Set<String> EXCLUDE_FROM_LIST_TYPES = Set.of(
        "text", "longtext", "json", "jsonb", "blob", "bytea", "clob"
    );
    private static final Set<String> SYSTEM_AUDIT_FIELDS = Set.of(
        "createdAt", "created_at", "updatedAt", "updated_at",
        "createdBy", "created_by", "updatedBy", "updated_by",
        "deletedFlag", "deleted_flag", "tenantId", "tenant_id", "version"
    );
    private static final int MAX_DEFAULT_COLUMNS = 8;
    private static final int MAX_DEFAULT_FILTERS = 3;

    private final MetaModelService metaModelService;

    /**
     * Generate default blocks for a given kind + modelCode.
     *
     * @param kind      "list" / "detail" / "form"
     * @param modelCode bound model code (must resolve via MetaModelService)
     * @return ordered list of block maps; empty when kind is unknown or model not found
     */
    public List<Map<String, Object>> generate(String kind, String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return List.of();
        }
        ModelDefinition def = metaModelService.getDefinitionByCode(modelCode);
        if (def == null) {
            return List.of();
        }
        return generate(kind, def);
    }

    /**
     * Variant accepting a resolved {@link ModelDefinition} directly. Used when
     * the caller already has the definition in hand (and for unit tests that
     * supply synthetic field lists without round-tripping through the DB).
     */
    public List<Map<String, Object>> generate(String kind, ModelDefinition def) {
        if (def == null) return List.of();
        String k = kind == null ? "" : kind;
        return switch (k) {
            case "list" -> generateListBlocks(def);
            case "detail" -> generateDetailBlocks(def);
            case "form" -> generateFormBlocks(def);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> generateListBlocks(ModelDefinition def) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        ModelCapabilities caps = def.getCapabilities() != null ? def.getCapabilities() : ModelCapabilities.empty();

        // 1. filters
        List<String> filterFields = pickDefaultFilterFields(caps);
        Map<String, Object> filtersBlock = new LinkedHashMap<>();
        filtersBlock.put("id", "filters_default");
        filtersBlock.put("blockType", "filters");
        filtersBlock.put("fields", filterFields);
        filtersBlock.put("actions", List.of("search", "reset"));
        blocks.add(filtersBlock);

        // 2. toolbar
        List<Map<String, Object>> buttons = new ArrayList<>();
        if (caps.isCreate()) buttons.add(preset("create"));
        if (caps.isExport()) buttons.add(preset("export"));
        if (caps.isBulkDelete()) {
            Map<String, Object> bulkDel = preset("bulkDelete");
            bulkDel.put("requiresSelection", true);
            buttons.add(bulkDel);
        }
        Map<String, Object> toolbarBlock = new LinkedHashMap<>();
        toolbarBlock.put("id", "toolbar_default");
        toolbarBlock.put("blockType", "toolbar");
        toolbarBlock.put("buttons", buttons);
        blocks.add(toolbarBlock);

        // 3. table
        List<String> columns = pickDefaultColumns(def);
        Map<String, Object> tableBlock = new LinkedHashMap<>();
        tableBlock.put("id", "table_default");
        tableBlock.put("blockType", "table");
        tableBlock.put("columns", columns);
        tableBlock.put("span", 12);
        tableBlock.put("dataSource", "tableData");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pageSize", 20);
        if (caps.isBulkDelete()) props.put("multiSelect", true);
        String defaultSort = pickDefaultSortField(caps);
        if (defaultSort != null) {
            props.put("defaultSortField", defaultSort);
            props.put("defaultSortOrder", "desc");
        }
        if (caps.isDetail()) {
            props.put("rowClickAction", "detail");
        }
        tableBlock.put("props", props);

        blocks.add(tableBlock);
        return blocks;
    }

    private List<Map<String, Object>> generateDetailBlocks(ModelDefinition def) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        ModelCapabilities caps = def.getCapabilities() != null ? def.getCapabilities() : ModelCapabilities.empty();

        // Top-level actions toolbar (if any applicable)
        List<Map<String, Object>> buttons = new ArrayList<>();
        if (caps.isUpdate()) buttons.add(preset("edit"));
        if (caps.isDelete()) buttons.add(preset("delete"));
        if (!buttons.isEmpty()) {
            Map<String, Object> actionsTop = new LinkedHashMap<>();
            actionsTop.put("id", "actions_top");
            actionsTop.put("blockType", "toolbar");
            actionsTop.put("buttons", buttons);
            blocks.add(actionsTop);
        }

        // Partition fields: main vs audit
        List<String> mainFields = new ArrayList<>();
        List<String> auditFields = new ArrayList<>();
        if (def.getFields() != null) {
            for (FieldDefinition f : def.getFields()) {
                if (f.getCode() == null) continue;
                if (SYSTEM_AUDIT_FIELDS.contains(f.getCode())) {
                    auditFields.add(f.getCode());
                } else {
                    mainFields.add(f.getCode());
                }
            }
        }

        if (!mainFields.isEmpty()) {
            Map<String, Object> main = new LinkedHashMap<>();
            main.put("id", "section_main");
            main.put("blockType", "detail-section");
            main.put("title", "基本信息");
            main.put("columns", 2);
            main.put("fields", mainFields);
            blocks.add(main);
        }

        if (!auditFields.isEmpty()) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("id", "section_audit");
            audit.put("blockType", "detail-section");
            audit.put("title", "系统信息");
            audit.put("columns", 2);
            audit.put("fields", auditFields);
            audit.put("collapsible", true);
            audit.put("defaultCollapsed", true);
            blocks.add(audit);
        }

        return blocks;
    }

    private List<Map<String, Object>> generateFormBlocks(ModelDefinition def) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<String> writableFields = new ArrayList<>();
        if (def.getFields() != null) {
            for (FieldDefinition f : def.getFields()) {
                if (f.getCode() == null) continue;
                if (SYSTEM_AUDIT_FIELDS.contains(f.getCode())) continue;
                if (f.isPrimaryKey()) continue;
                // NOTE: FieldDefinition does not expose writable/hidden flags at this revision.
                // Audit + primary key exclusion is the current heuristic; when FieldDefinition
                // gains explicit `writable` / `hidden` flags, extend this filter accordingly.
                if (f.isVirtual() && f.isComputedReadonly()) continue;
                writableFields.add(f.getCode());
            }
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("id", "form_section_main");
        section.put("blockType", "form-section");
        section.put("title", "基本信息");
        section.put("columns", 2);
        section.put("fields", writableFields);
        blocks.add(section);

        Map<String, Object> buttonsBlock = new LinkedHashMap<>();
        buttonsBlock.put("id", "form_buttons");
        buttonsBlock.put("blockType", "form-buttons");
        buttonsBlock.put("buttons", List.of(
            Map.of("preset", "save"),
            Map.of("preset", "cancel")
        ));
        blocks.add(buttonsBlock);

        return blocks;
    }

    private List<String> pickDefaultColumns(ModelDefinition def) {
        List<String> result = new ArrayList<>();
        List<FieldDefinition> all = def.getFields() != null ? def.getFields() : List.of();

        // Priority-ordered pass
        for (String p : PRIORITY_FIELDS) {
            for (FieldDefinition f : all) {
                if (p.equals(f.getCode()) && !isExcludedFromList(f)) {
                    if (!result.contains(f.getCode())) result.add(f.getCode());
                    break;
                }
            }
        }
        // Declaration-order fill
        for (FieldDefinition f : all) {
            if (result.size() >= MAX_DEFAULT_COLUMNS) break;
            if (f.getCode() == null) continue;
            if (!result.contains(f.getCode()) && !isExcludedFromList(f)) {
                result.add(f.getCode());
            }
        }
        return result;
    }

    private boolean isExcludedFromList(FieldDefinition f) {
        if (f.getCode() == null) return true;
        String type = f.getDataType() != null
            ? f.getDataType().toLowerCase(Locale.ROOT)
            : "";
        return EXCLUDE_FROM_LIST_TYPES.contains(type);
    }

    private List<String> pickDefaultFilterFields(ModelCapabilities caps) {
        if (!caps.isFilter()
            || caps.getFilterableFields() == null
            || caps.getFilterableFields().isEmpty()) {
            return List.of();
        }
        List<String> whitelist = caps.getFilterableFields();
        List<String> priority = List.of("status", "code", "name", "title");
        List<String> result = new ArrayList<>();
        for (String p : priority) {
            if (result.size() >= MAX_DEFAULT_FILTERS) break;
            if (whitelist.contains(p) && !result.contains(p)) result.add(p);
        }
        // Fill remaining from whitelist in declared order
        for (String code : whitelist) {
            if (result.size() >= MAX_DEFAULT_FILTERS) break;
            if (!result.contains(code)) result.add(code);
        }
        return result;
    }

    private String pickDefaultSortField(ModelCapabilities caps) {
        if (!caps.isSort() || caps.getSortableFields() == null) return null;
        for (String candidate : List.of("createdAt", "created_at")) {
            if (caps.getSortableFields().contains(candidate)) return candidate;
        }
        return null;
    }

    private Map<String, Object> preset(String presetCode) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("preset", presetCode);
        return b;
    }
}
