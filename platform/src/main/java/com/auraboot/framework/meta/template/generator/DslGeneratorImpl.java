package com.auraboot.framework.meta.template.generator;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * DSL Generator implementation
 *
 * @author AuraBoot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DslGeneratorImpl implements DslGenerator {

    private final ObjectMapper objectMapper;
    private final FieldTypeMapper fieldTypeMapper;
    private final MetaFieldDictBindingMapper fieldDictBindingMapper;

    private String buildLocalizedTitleJson(String zhTitle, String enTitle) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "zh-CN", zhTitle,
                    "en-US", enTitle
            ));
        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to build localized page title", e);
        }
    }

    /**
     * Load dictionary bindings for fields and create a map of fieldId -> dictCode
     */
    private Map<Long, String> loadDictBindings(List<Field> fields) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null || fields.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> fieldPids = fields.stream()
                .map(Field::getPid)
                .filter(pid -> pid != null)
                .collect(Collectors.toList());

        if (fieldPids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<FieldDictBinding> bindings = fieldDictBindingMapper.findByFieldPids(fieldPids, tenantId);

        // Create map: fieldId -> dictCode
        Map<String, Long> pidToIdMap = fields.stream()
                .filter(f -> f.getPid() != null)
                .collect(Collectors.toMap(Field::getPid, Field::getId));

        Map<Long, String> result = new HashMap<>();
        for (FieldDictBinding binding : bindings) {
            Long fieldId = pidToIdMap.get(binding.getFieldPid());
            if (fieldId != null && binding.getDictCode() != null) {
                result.put(fieldId, binding.getDictCode());
            }
        }

        return result;
    }

    private String resolveTitleField(Model model, List<ModelFieldBinding> bindings, List<Field> fields) {
        Object declared = model.getExtension() == null ? null : model.getExtension().get("titleField");
        Set<Long> visible = bindings.stream().filter(b -> Boolean.TRUE.equals(b.getVisible()))
                .map(ModelFieldBinding::getFieldId).collect(Collectors.toSet());
        return fields.stream().filter(f -> visible.contains(f.getId()))
                .filter(f -> f.getFeature() == null || !Boolean.TRUE.equals(f.getFeature().getHidden()))
                .filter(f -> declared instanceof String ? declared.equals(f.getCode())
                        : f.getExtension() != null && Boolean.TRUE.equals(f.getExtension().get("displayField")))
                .map(Field::getCode).findFirst().orElse(null);
    }

    private boolean needsFullWidth(Field field) {
        return Set.of("smarttextarea", "smartrichtext", "smartrichtexteditor", "smartfileattachment", "smartupload", "smartjsoneditor", "richtext", "textarea", "fileattachment")
                .contains(fieldTypeMapper.getFormComponent(field).toLowerCase(Locale.ROOT));
    }

    @Override
    public PageSchema generateListPage(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config
    ) {
        bindings = bindings.stream().sorted(Comparator.comparing(ModelFieldBinding::getFieldOrder,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        // Load dictionary bindings for fields
        Map<Long, String> dictBindings = loadDictBindings(fields);

        // Build V2 flat blocks list
        List<Map<String, Object>> blocks = new ArrayList<>();

        // Collect blocks from filter, toolbar, and table areas
        Map<String, Object> filterArea = buildFilterArea(model, bindings, fields, dictBindings);
        addBlocksFromArea(blocks, filterArea);

        Map<String, Object> toolbarArea = buildToolbarArea(model, config);
        addBlocksFromArea(blocks, toolbarArea);

        Map<String, Object> tableArea = buildTableArea(model, bindings, fields, config, dictBindings);
        addBlocksFromArea(blocks, tableArea);

        // Convert blocks to JSON string
        String blocksJson;
        String layoutJson;
        try {
            blocksJson = objectMapper.writeValueAsString(blocks);
            layoutJson = objectMapper.writeValueAsString(Map.of("type", "stack"));
        } catch (JsonProcessingException e) {
            log.error("Failed to generate list page DSL for model: {}", model.getCode(), e);
            throw new BusinessException("Failed to generate list page DSL", e);
        }

        // Create PageSchema entity
        PageSchema pageSchema = new PageSchema();
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setName(model.getCode() + "_list");
        pageSchema.setTitle(buildLocalizedTitleJson(
                model.getDisplayName() + "列表",
                model.getDisplayName() + " List"
        ));
        pageSchema.setKind("list");
        pageSchema.setSchemaVersion(com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION);
        pageSchema.setProfile("admin");
        pageSchema.setBlocks(blocksJson);
        pageSchema.setLayout(layoutJson);
        pageSchema.setStatus(StatusConstants.PUBLISHED);
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);

        return pageSchema;
    }

    @Override
    public PageSchema generateFormPage(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config
    ) {
        bindings = bindings.stream().sorted(Comparator.comparing(ModelFieldBinding::getFieldOrder,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        // Load dictionary bindings for fields
        Map<Long, String> dictBindings = loadDictBindings(fields);

        // Build V2 flat blocks list
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> formMainArea = buildFormMainArea(model, bindings, fields, config, dictBindings);
        addBlocksFromArea(blocks, formMainArea);

        // Convert blocks to JSON string
        String blocksJson;
        String layoutJson;
        try {
            blocksJson = objectMapper.writeValueAsString(blocks);
            layoutJson = objectMapper.writeValueAsString(Map.of("type", "stack"));
        } catch (JsonProcessingException e) {
            log.error("Failed to generate form page DSL for model: {}", model.getCode(), e);
            throw new BusinessException("Failed to generate form page DSL", e);
        }

        // Create PageSchema entity
        PageSchema pageSchema = new PageSchema();
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setName(model.getCode() + "_form");
        pageSchema.setTitle(buildLocalizedTitleJson(
                model.getDisplayName() + "表单",
                model.getDisplayName() + " Form"
        ));
        Map<String, Object> formExtension = new LinkedHashMap<>();
        formExtension.put("createTitle", Map.of("zh-CN", "新建" + model.getDisplayName(), "en", "New " + model.getDisplayName()));
        formExtension.put("editTitle", Map.of("zh-CN", "编辑" + model.getDisplayName(), "en", "Edit " + model.getDisplayName()));
        if (config.getFormPresentation() != null) {
            Integer columns = config.getFormPresentation().getColumns();
            if (columns == null || columns < 1 || columns > 3) {
                throw new BusinessException("Form columns must be between 1 and 3");
            }
            formExtension.put("formPresentation", objectMapper.convertValue(
                    config.getFormPresentation(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        }
        var formExtensionBean = new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        formExtensionBean.setExtension(formExtension);
        pageSchema.setExtension(formExtensionBean);
        pageSchema.setKind("form");
        pageSchema.setSchemaVersion(com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION);
        pageSchema.setProfile("admin");
        pageSchema.setBlocks(blocksJson);
        pageSchema.setLayout(layoutJson);
        pageSchema.setStatus(StatusConstants.PUBLISHED);
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);

        return pageSchema;
    }

    @Override
    public PageSchema generateDetailPage(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config
    ) {
        bindings = bindings.stream().sorted(Comparator.comparing(ModelFieldBinding::getFieldOrder,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        // Load dictionary bindings for fields
        Map<Long, String> dictBindings = loadDictBindings(fields);

        // Build V2 flat blocks list
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> detailMainArea = buildDetailMainArea(model, bindings, fields, config, dictBindings);
        addBlocksFromArea(blocks, detailMainArea);

        // Convert blocks to JSON string
        String blocksJson;
        String layoutJson;
        try {
            blocksJson = objectMapper.writeValueAsString(blocks);
            layoutJson = objectMapper.writeValueAsString(Map.of("type", "stack"));
        } catch (JsonProcessingException e) {
            log.error("Failed to generate detail page DSL for model: {}", model.getCode(), e);
            throw new BusinessException("Failed to generate detail page DSL", e);
        }

        // Create PageSchema entity
        PageSchema pageSchema = new PageSchema();
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setName(model.getCode() + "_detail");
        pageSchema.setTitle(buildLocalizedTitleJson(
                model.getDisplayName() + "详情",
                model.getDisplayName() + " Detail"
        ));
        String titleField = resolveTitleField(model, bindings, fields);
        if (titleField != null) {
            var extension = new com.auraboot.framework.meta.entity.payload.ExtensionBean();
            extension.setExtension(Map.of("headerTitleField", titleField));
            pageSchema.setExtension(extension);
        }
        pageSchema.setKind("detail");
        pageSchema.setSchemaVersion(com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION);
        pageSchema.setProfile("admin");
        pageSchema.setBlocks(blocksJson);
        pageSchema.setLayout(layoutJson);
        pageSchema.setStatus(StatusConstants.PUBLISHED);
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);

        return pageSchema;
    }

    /**
     * Extract blocks from a V1 area map and add them to the flat blocks list.
     * Each area has a "blocks" key containing a list of block maps.
     */
    @SuppressWarnings("unchecked")
    private void addBlocksFromArea(List<Map<String, Object>> target, Map<String, Object> area) {
        Object areaBlocks = area.get("blocks");
        if (areaBlocks instanceof List) {
            target.addAll((List<Map<String, Object>>) areaBlocks);
        }
    }

    private Map<String, Object> buildFilterArea(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            Map<Long, String> dictBindings
    ) {
        Map<String, Object> area = new LinkedHashMap<>();
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> filterBlock = new LinkedHashMap<>();
        filterBlock.put("id", "block_" + model.getCode() + "_filters");
        filterBlock.put("blockType", "filters");

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        filterBlock.put("layout", layout);

        filterBlock.put("model", "{{state.filters}}");

        // Build filter fields (first 3 searchable fields)
        List<Map<String, Object>> filterFields = new ArrayList<>();
        int count = 0;
        for (ModelFieldBinding binding : bindings) {
            if (count >= 3) break;

            Field field = fields.stream()
                    .filter(f -> f.getId().equals(binding.getFieldId()))
                    .findFirst()
                    .orElse(null);

            if (field != null && isSearchableField(field)) {
                String dictCode = dictBindings.get(field.getId());
                filterFields.add(buildFilterField(model, field, binding, dictCode));
                count++;
            }
        }

        filterBlock.put("fields", filterFields);

        // Build filter buttons
        List<Map<String, Object>> buttons = new ArrayList<>();
        buttons.add(buildSearchButton(model));
        buttons.add(buildResetButton(model));
        filterBlock.put("buttons", buttons);

        blocks.add(filterBlock);
        area.put("blocks", blocks);

        return area;
    }

    private boolean isSearchableField(Field field) {
        if (field == null || field.getDataType() == null) {
            return false;
        }
        String dataType = field.getDataType().toLowerCase(Locale.ROOT);
        return "string".equals(dataType) || "enum".equals(dataType) || "reference".equals(dataType);
    }

    private Map<String, Object> buildFilterField(
            Model model,
            Field field,
            ModelFieldBinding binding,
            String dictCode
    ) {
        Map<String, Object> filterField = new LinkedHashMap<>();
        filterField.put("field", field.getCode());
        // Label resolved by frontend: model.{modelCode}.{fieldCode}.label
        filterField.put("component", fieldTypeMapper.getFormComponent(field));

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 4);
        layout.put("rowSpan", 1);
        filterField.put("layout", layout);

        // Add dictCode if field has dictionary binding
        if (dictCode != null && !dictCode.isEmpty()) {
            filterField.put("dictCode", dictCode);
        }

        // Placeholder resolved by frontend: model.{modelCode}.{fieldCode}.placeholder

        return filterField;
    }

    private Map<String, Object> buildSearchButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "search");
        button.put("action", "search");
        // Label resolved by frontend: action.search.label
        button.put("primary", true);

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> onClick = new LinkedHashMap<>();
        onClick.put("handler", "search");
        events.put("onClick", onClick);
        button.put("events", events);

        return button;
    }

    private Map<String, Object> buildResetButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "reset");
        button.put("action", "reset");
        // Label resolved by frontend: action.reset.label

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> onClick = new LinkedHashMap<>();
        onClick.put("handler", "reset");
        events.put("onClick", onClick);
        button.put("events", events);

        return button;
    }

    private Map<String, Object> buildToolbarArea(Model model, CrudTemplateConfig config) {
        Map<String, Object> area = new LinkedHashMap<>();
        List<Map<String, Object>> blocks = new ArrayList<>();

        // Selected info block
        Map<String, Object> selectedInfoBlock = new LinkedHashMap<>();
        selectedInfoBlock.put("id", "block_selectedInfo");
        selectedInfoBlock.put("blockType", "text");
        Map<String, Object> infoLayout = new LinkedHashMap<>();
        infoLayout.put("colSpan", 6);
        infoLayout.put("rowSpan", 1);
        selectedInfoBlock.put("layout", infoLayout);
        selectedInfoBlock.put("contentKey", "selectedInfo");
        // Content resolved by frontend: table.selected with interpolation {count}
        selectedInfoBlock.put("visibleWhen", "${state.selectedIds.length > 0}");
        blocks.add(selectedInfoBlock);

        // Toolbar buttons block
        Map<String, Object> toolbarBlock = new LinkedHashMap<>();
        toolbarBlock.put("id", "block_" + model.getCode() + "_toolbar");
        toolbarBlock.put("blockType", "form-buttons");
        Map<String, Object> toolbarLayout = new LinkedHashMap<>();
        toolbarLayout.put("colSpan", 6);
        toolbarLayout.put("rowSpan", 1);
        toolbarBlock.put("layout", toolbarLayout);

        List<Map<String, Object>> buttons = new ArrayList<>();

        // Create button
        buttons.add(buildCreateButton(model));

        // Export button (if enabled)
        if (config.isEnableExport()) {
            buttons.add(buildExportButton(model));
        }

        // Import button (if enabled)
        if (config.isEnableImport()) {
            buttons.add(buildImportButton(model));
        }

        // Delete selected button
        buttons.add(buildDeleteSelectedButton(model));

        toolbarBlock.put("buttons", buttons);
        blocks.add(toolbarBlock);

        area.put("blocks", blocks);
        return area;
    }

    private Map<String, Object> buildCreateButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "create");
        button.put("action", Map.of("type", "builtin", "name", "new"));
        button.put("label", Map.of("zh-CN", "新建", "en", "New"));
        // Label resolved by frontend: action.create.label
        button.put("primary", true);
        // 权限码格式: model.{modelCode}.create
        button.put("permissionCode", "model." + model.getCode() + ".create");

        return button;
    }

    private Map<String, Object> buildExportButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "export");
        button.put("action", "export");
        // Label resolved by frontend: action.export.label
        // 权限码格式: model.{modelCode}.export
        button.put("permissionCode", "model." + model.getCode() + ".export");

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> onClick = new LinkedHashMap<>();
        onClick.put("handler", "exportData");
        events.put("onClick", onClick);
        button.put("events", events);

        return button;
    }

    private Map<String, Object> buildImportButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "import");
        button.put("action", "import");
        // Label resolved by frontend: action.import.label
        // 权限码格式: model.{modelCode}.import
        button.put("permissionCode", "model." + model.getCode() + ".import");

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> onClick = new LinkedHashMap<>();
        onClick.put("handler", "importData");
        events.put("onClick", onClick);
        button.put("events", events);

        return button;
    }

    private Map<String, Object> buildDeleteSelectedButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "deleteSelected");
        button.put("action", "deleteSelected");
        // Label resolved by frontend: action.deleteSelected
        button.put("danger", true);
        // 权限码格式: model.{modelCode}.delete
        button.put("permissionCode", "model." + model.getCode() + ".delete");
        button.put("enableWhen", "${state.selectedCount > 0}");

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> onClick = new LinkedHashMap<>();
        onClick.put("handler", "deleteSelected");
        events.put("onClick", onClick);
        button.put("events", events);

        return button;
    }

    private Map<String, Object> buildTableArea(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config,
            Map<Long, String> dictBindings
    ) {
        Map<String, Object> area = new LinkedHashMap<>();
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> tableBlock = new LinkedHashMap<>();
        tableBlock.put("id", "block_" + model.getCode() + "_table");
        tableBlock.put("blockType", "table");

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        tableBlock.put("layout", layout);

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("rowKey", "id");
        table.put("dataSource", "ds_" + model.getCode() + "List");

        // Pagination
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("pageSize", 20);
        pagination.put("pageSizeOptions", Arrays.asList(10, 20, 50, 100));
        pagination.put("showTotal", true);
        table.put("pagination", pagination);

        // Selection
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("mode", "multiple");
        selection.put("bind", "{{state.selectedIds}}");
        table.put("selection", selection);

        // Columns
        List<Map<String, Object>> columns = buildTableColumns(model, bindings, fields, config, dictBindings);
        table.put("columns", columns);

        tableBlock.put("table", table);
        blocks.add(tableBlock);

        area.put("blocks", blocks);
        return area;
    }

    private List<Map<String, Object>> buildTableColumns(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config,
            Map<Long, String> dictBindings
    ) {
        List<Map<String, Object>> columns = new ArrayList<>();

        // Determine which fields to display
        List<String> displayFields = config.getListColumns();
        if (displayFields == null || displayFields.isEmpty()) {
            String titleField = resolveTitleField(model, bindings, fields);
            List<Field> candidates = bindings.stream().filter(b -> Boolean.TRUE.equals(b.getVisible()))
                    .map(b -> fields.stream().filter(f -> f.getId().equals(b.getFieldId())).findFirst().orElse(null))
                    .filter(Objects::nonNull)
                    .filter(f -> f.getFeature() == null || !Boolean.TRUE.equals(f.getFeature().getHidden()))
                    .sorted(Comparator.comparing(f -> !Objects.equals(f.getCode(), titleField)))
                    .toList();
            List<Field> compact = candidates.stream()
                    .filter(f -> Objects.equals(f.getCode(), titleField) || !needsFullWidth(f)).limit(7).toList();
            // A text-only model must still have a useful list.
            for (Field field : compact.isEmpty() ? candidates.stream().limit(7).toList() : compact) {
                Map<String, Object> column = buildTableColumn(model, field, dictBindings.get(field.getId()));
                if (Objects.equals(field.getCode(), titleField)) {
                    column.put("presentation", Map.of("kind", "object"));
                }
                columns.add(column);
            }
        } else {
            // Display specified fields only
            for (String fieldCode : displayFields) {
                Field field = fields.stream()
                        .filter(f -> fieldCode.equals(f.getCode()))
                        .findFirst()
                        .orElse(null);

                if (field != null) {
                    String dictCode = dictBindings.get(field.getId());
                    columns.add(buildTableColumn(model, field, dictCode));
                }
            }
        }

        // Add action column
        columns.add(buildActionColumn(model));

        return columns;
    }

    private Map<String, Object> buildTableColumn(Model model, Field field, String dictCode) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("field", field.getCode());
        // Label resolved by frontend: model.{modelCode}.{fieldCode}.label

        String valueType = fieldTypeMapper.getColumnValueType(field);
        if (!"text".equals(valueType)) {
            column.put("valueType", valueType);
        }

        // Add sortable for common fields
        if (isCommonSortableField(field)) {
            column.put("sortable", true);
        }

        // Add dictCode if field has dictionary binding
        if (dictCode != null && !dictCode.isEmpty()) {
            column.put("dictCode", dictCode);
        }

        return column;
    }

    private boolean isCommonSortableField(Field field) {
        if (field == null || field.getCode() == null) {
            return false;
        }
        String code = field.getCode().toLowerCase();
        return code.contains("code") || code.contains("name") ||
                code.contains("created") || code.contains("updated");
    }

    private Map<String, Object> buildActionColumn(Model model) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("field", "actions");
        column.put("isActionColumn", true);
        // Label resolved by frontend: table.actions

        List<Map<String, Object>> buttons = new ArrayList<>();

        // View button
        Map<String, Object> viewBtn = new LinkedHashMap<>();
        viewBtn.put("code", "view");
        viewBtn.put("action", Map.of("type", "builtin", "name", "view"));
        viewBtn.put("label", Map.of("zh-CN", "查看", "en", "View"));
        viewBtn.put("permissionCode", "model." + model.getCode() + ".read");
        // Label resolved by frontend: action.view.label
        buttons.add(viewBtn);

        // Edit button
        Map<String, Object> editBtn = new LinkedHashMap<>();
        editBtn.put("code", "edit");
        editBtn.put("action", Map.of("type", "builtin", "name", "edit"));
        editBtn.put("label", Map.of("zh-CN", "编辑", "en", "Edit"));
        // Label resolved by frontend: action.update
        // 权限码格式: model.{modelCode}.update
        editBtn.put("permissionCode", "model." + model.getCode() + ".update");
        buttons.add(editBtn);

        // Delete button
        Map<String, Object> deleteBtn = new LinkedHashMap<>();
        deleteBtn.put("code", "delete");
        deleteBtn.put("action", "delete");
        // Label resolved by frontend: action.delete.label
        deleteBtn.put("danger", true);
        // 权限码格式: model.{modelCode}.delete
        deleteBtn.put("permissionCode", "model." + model.getCode() + ".delete");
        Map<String, Object> deleteEvents = new LinkedHashMap<>();
        Map<String, Object> deleteOnClick = new LinkedHashMap<>();
        deleteOnClick.put("handler", "deleteSingle");
        Map<String, Object> deleteArgs = new LinkedHashMap<>();
        deleteArgs.put("id", "${row.id}");
        deleteOnClick.put("args", deleteArgs);
        deleteEvents.put("onClick", deleteOnClick);
        deleteBtn.put("events", deleteEvents);
        buttons.add(deleteBtn);

        column.put("buttons", buttons);
        return column;
    }

    private Map<String, Object> buildFormMainArea(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config,
            Map<Long, String> dictBindings
    ) {
        Map<String, Object> area = new LinkedHashMap<>();
        List<Map<String, Object>> blocks = new ArrayList<>();

        // Build form section
        Map<String, Object> formSection = new LinkedHashMap<>();
        formSection.put("id", "block_" + model.getCode() + "_form");
        formSection.put("blockType", "form-section");
        // Title resolved by frontend: page.form.{modelCode}.section or default "基本信息"

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        formSection.put("layout", layout);

        // Build form fields
        List<Map<String, Object>> formFields = buildFormFields(model, bindings, fields, config, dictBindings);
        formSection.put("fields", formFields);

        if (config.getFormGroups() == null || config.getFormGroups().isEmpty()) {
            blocks.add(formSection);
        } else {
            java.util.Set<String> assigned = new java.util.HashSet<>();
            for (CrudTemplateConfig.FormGroup group : config.getFormGroups()) {
                if (group.getFields() == null || group.getFields().isEmpty()) continue;
                List<Map<String, Object>> groupedFields = new ArrayList<>();
                for (String code : group.getFields()) {
                    Map<String, Object> entry = formFields.stream()
                            .filter(candidate -> code.equals(candidate.get("field"))).findFirst()
                            .orElseThrow(() -> new BusinessException("Unknown form group field: " + code));
                    if (!assigned.add(code)) throw new BusinessException("Duplicate form group field: " + code);
                    groupedFields.add(entry);
                }
                Map<String, Object> section = new LinkedHashMap<>(formSection);
                section.put("id", formSection.get("id") + "_" + blocks.size());
                section.put("title", group.getTitle());
                section.put("fields", groupedFields);
                section.put("extension", Map.of("collapsible", group.isCollapsible(), "defaultCollapsed", group.isDefaultCollapsed()));
                blocks.add(section);
            }
            List<Map<String, Object>> remaining = formFields.stream()
                    .filter(entry -> !assigned.contains(entry.get("field"))).toList();
            if (!remaining.isEmpty()) {
                formSection.put("fields", remaining);
                blocks.add(formSection);
            }
        }

        // Add form buttons
        blocks.add(buildFormButtons(model));

        area.put("blocks", blocks);
        return area;
    }

    private List<Map<String, Object>> buildFormFields(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config,
            Map<Long, String> dictBindings
    ) {
        List<Map<String, Object>> formFields = new ArrayList<>();

        // Determine which fields to display
        List<String> displayFields = config.getFormFields();

        if (displayFields == null || displayFields.isEmpty()) {
            // Display all editable fields
            for (ModelFieldBinding binding : bindings) {
                if (!Boolean.FALSE.equals(binding.getVisible()) &&
                        (Boolean.TRUE.equals(binding.getEditable()) || Boolean.TRUE.equals(binding.getVisible()))) {
                    Field field = fields.stream()
                            .filter(f -> f.getId().equals(binding.getFieldId()))
                            .findFirst()
                            .orElse(null);

                    if (field != null && (field.getFeature() == null || !Boolean.TRUE.equals(field.getFeature().getHidden()))) {
                        String dictCode = dictBindings.get(field.getId());
                        formFields.add(buildFormField(model, field, binding, dictCode));
                    }
                }
            }
        } else {
            // Display specified fields only
            for (String fieldCode : displayFields) {
                Field field = fields.stream()
                        .filter(f -> fieldCode.equals(f.getCode()))
                        .findFirst()
                        .orElse(null);

                if (field != null && (field.getFeature() == null || !Boolean.TRUE.equals(field.getFeature().getHidden()))) {
                    ModelFieldBinding binding = bindings.stream()
                            .filter(b -> b.getFieldId().equals(field.getId()))
                            .findFirst()
                            .orElse(null);

                    if (binding != null && !Boolean.FALSE.equals(binding.getVisible())) {
                        String dictCode = dictBindings.get(field.getId());
                        formFields.add(buildFormField(model, field, binding, dictCode));
                    }
                }
            }
        }

        return formFields;
    }

    private Map<String, Object> buildFormField(
            Model model,
            Field field,
            ModelFieldBinding binding,
            String dictCode
    ) {
        Map<String, Object> formField = new LinkedHashMap<>();
        formField.put("field", field.getCode());
        // Label resolved by frontend: model.{modelCode}.{fieldCode}.label
        String component = fieldTypeMapper.getFormComponent(field);
        formField.put("component", component);
        boolean readOnly = Boolean.FALSE.equals(binding.getEditable()) ||
                (field.getFeature() != null && Boolean.TRUE.equals(field.getFeature().getReadonly()));
        if (readOnly) formField.put("readOnly", true);
        if (field.getRefTarget() != null) {
            formField.put("refTarget", objectMapper.convertValue(field.getRefTarget(), Map.class));
        }
        if (field.getUiSchema() != null) {
            if (field.getUiSchema().getHelpText() != null) formField.put("helpText", field.getUiSchema().getHelpText());
            if (field.getUiSchema().getPlaceholder() != null) formField.put("placeholder", field.getUiSchema().getPlaceholder());
        }

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", needsFullWidth(field) ? 12 : 6);
        layout.put("rowSpan", 1);
        formField.put("layout", layout);

        // Add dictCode if field has dictionary binding
        if (dictCode != null && !dictCode.isEmpty()) {
            formField.put("dictCode", dictCode);
        }

        // Add props (only non-i18n props)
        Map<String, Object> props = new LinkedHashMap<>();
        // Placeholder resolved by frontend: model.{modelCode}.{fieldCode}.placeholder

        if ("datetime".equalsIgnoreCase(field.getDataType()) || "timestamp".equalsIgnoreCase(field.getDataType())) {
            props.put("dateType", "datetime-local");
        }

        // Add number type for number fields
        if (fieldTypeMapper.isNumberField(field)) {
            props.put("type", "number");
        }

        if (!props.isEmpty()) {
            formField.put("props", props);
        }

        // Add validation rules
        if (!readOnly && Boolean.TRUE.equals(binding.getRequired())) {
            List<Map<String, Object>> validation = new ArrayList<>();
            Map<String, Object> requiredRule = new LinkedHashMap<>();
            requiredRule.put("type", "required");
            // Message resolved by frontend: message.validation.required with field label interpolation
            validation.add(requiredRule);
            formField.put("validation", validation);
        }

        // Add default value if exists
        if (binding.getDefaultValue() != null && !binding.getDefaultValue().isEmpty()) {
            formField.put("defaultValue", binding.getDefaultValue());
        }

        return formField;
    }

    private Map<String, Object> buildFormButtons(Model model) {
        Map<String, Object> buttonsBlock = new LinkedHashMap<>();
        buttonsBlock.put("id", "block_" + model.getCode() + "_buttons");
        buttonsBlock.put("blockType", "form-buttons");

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        buttonsBlock.put("layout", layout);

        List<Map<String, Object>> buttons = new ArrayList<>();

        // Submit button
        Map<String, Object> submitBtn = new LinkedHashMap<>();
        submitBtn.put("code", "submit");
        submitBtn.put("action", "save");
        submitBtn.put("label", Map.of("zh-CN", "保存", "en", "Save"));
        // Label resolved by frontend: action.save.label
        submitBtn.put("primary", true);
        // 权限码格式: model.{modelCode}.create/update
        submitBtn.put("visibleWhen", "${hasPermission('model." + model.getCode() + ".create') || hasPermission('model." + model.getCode() + ".update')}");
        buttons.add(submitBtn);

        // Reset button
        Map<String, Object> resetBtn = new LinkedHashMap<>();
        resetBtn.put("code", "reset");
        resetBtn.put("action", "reset");
        // Label resolved by frontend: action.reset.label
        buttons.add(resetBtn);

        // Cancel button
        Map<String, Object> cancelBtn = new LinkedHashMap<>();
        cancelBtn.put("code", "cancel");
        cancelBtn.put("action", "cancel");
        // Label resolved by frontend: action.cancel.label
        buttons.add(cancelBtn);

        buttonsBlock.put("buttons", buttons);
        return buttonsBlock;
    }

    private Map<String, Object> buildDetailMainArea(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config,
            Map<Long, String> dictBindings
    ) {
        Map<String, Object> area = new LinkedHashMap<>();
        List<Map<String, Object>> blocks = new ArrayList<>();

        // Build detail section
        Map<String, Object> detailSection = new LinkedHashMap<>();
        detailSection.put("id", "block_" + model.getCode() + "_detail");
        detailSection.put("blockType", "detail-section");
        // Title resolved by frontend: page.detail.{modelCode}.section or default "详细信息"

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        detailSection.put("layout", layout);

        // Build detail fields
        List<Map<String, Object>> detailFields = buildDetailFields(model, bindings, fields, config, dictBindings);
        detailSection.put("fields", detailFields);

        blocks.add(detailSection);

        // Add action buttons
        blocks.add(buildDetailButtons(model));

        area.put("blocks", blocks);
        return area;
    }

    private List<Map<String, Object>> buildDetailFields(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config,
            Map<Long, String> dictBindings
    ) {
        List<Map<String, Object>> detailFields = new ArrayList<>();

        // Determine which fields to display
        List<String> displayFields = config.getDetailFields();

        if (displayFields == null || displayFields.isEmpty()) {
            // Display all visible fields
            for (ModelFieldBinding binding : bindings) {
                if (Boolean.TRUE.equals(binding.getVisible())) {
                    Field field = fields.stream()
                            .filter(f -> f.getId().equals(binding.getFieldId()))
                            .findFirst()
                            .orElse(null);

                    if (field != null) {
                        String dictCode = dictBindings.get(field.getId());
                        detailFields.add(buildDetailField(model, field, dictCode));
                    }
                }
            }
        } else {
            // Display specified fields only
            for (String fieldCode : displayFields) {
                Field field = fields.stream()
                        .filter(f -> fieldCode.equals(f.getCode()))
                        .findFirst()
                        .orElse(null);

                if (field != null) {
                    String dictCode = dictBindings.get(field.getId());
                    detailFields.add(buildDetailField(model, field, dictCode));
                }
            }
        }

        return detailFields;
    }

    private Map<String, Object> buildDetailField(Model model, Field field, String dictCode) {
        Map<String, Object> detailField = new LinkedHashMap<>();
        detailField.put("field", field.getCode());
        // Label resolved by frontend: model.{modelCode}.{fieldCode}.label
        detailField.put("component", "SmartText");
        detailField.put("readOnly", true);

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", needsFullWidth(field) ? 12 : 6);
        layout.put("rowSpan", 1);
        detailField.put("layout", layout);

        String valueType = fieldTypeMapper.getColumnValueType(field);
        if (!"text".equals(valueType)) {
            detailField.put("valueType", valueType);
        }

        // Add dictCode if field has dictionary binding
        if (dictCode != null && !dictCode.isEmpty()) {
            detailField.put("dictCode", dictCode);
        }

        return detailField;
    }

    private Map<String, Object> buildDetailButtons(Model model) {
        Map<String, Object> buttonsBlock = new LinkedHashMap<>();
        buttonsBlock.put("id", "block_" + model.getCode() + "_actions");
        buttonsBlock.put("blockType", "toolbar");

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        buttonsBlock.put("layout", layout);

        List<Map<String, Object>> buttons = new ArrayList<>();

        // Edit button
        Map<String, Object> editBtn = new LinkedHashMap<>();
        editBtn.put("code", "edit");
        editBtn.put("action", Map.of("type", "builtin", "name", "edit"));
        editBtn.put("label", Map.of("zh-CN", "编辑", "en", "Edit"));
        // Label resolved by frontend: action.update
        editBtn.put("primary", true);
        // 权限码格式: model.{modelCode}.update
        editBtn.put("permissionCode", "model." + model.getCode() + ".update");
        Map<String, Object> editEvents = new LinkedHashMap<>();
        Map<String, Object> editOnClick = new LinkedHashMap<>();
        editOnClick.put("handler", "openEditForm");
        editEvents.put("onClick", editOnClick);
        editBtn.put("events", editEvents);
        buttons.add(editBtn);

        buttonsBlock.put("buttons", buttons);
        return buttonsBlock;
    }

}
