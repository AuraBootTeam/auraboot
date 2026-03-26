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

    @Override
    public PageSchema generateListPage(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config
    ) {
        // Load dictionary bindings for fields
        Map<Long, String> dictBindings = loadDictBindings(fields);

        // Build UnifiedSchema JSON
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("kind", "List");
        schema.put("version", "1.0.0");
        schema.put("id", "list." + model.getCode());
        schema.put("modelCode", model.getCode());
        // Title resolved by frontend: page.list.{modelCode}.title -> model.{modelCode}._meta.label + "列表"

        // Build state binding
        Map<String, String> stateBinding = new LinkedHashMap<>();
        stateBinding.put("filters", "{{state.filters}}");
        stateBinding.put("selection", "{{state.selectedIds}}");
        schema.put("stateBinding", stateBinding);

        // Build layout
        schema.put("layout", buildListLayout());

        // Build areas
        Map<String, Object> areas = new LinkedHashMap<>();
        areas.put("filters", buildFilterArea(model, bindings, fields, dictBindings));
        areas.put("toolbar", buildToolbarArea(model, config));
        areas.put("main", buildTableArea(model, bindings, fields, config, dictBindings));
        schema.put("areas", areas);

        // Build dataSources
        schema.put("dataSources", buildListDataSources(model));

        // Build handlers
        schema.put("handlers", buildListHandlers(model));

        // Convert to JSON string
        String dslJson;
        try {
            dslJson = objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            log.error("Failed to generate list page DSL for model: {}", model.getCode(), e);
            throw new BusinessException("Failed to generate list page DSL", e);
        }

        // Create PageSchema entity
        PageSchema pageSchema = new PageSchema();
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setName(model.getCode() + "_list");
        pageSchema.setTitle(model.getDisplayName() + "列表");
        pageSchema.setPageType("list");
        pageSchema.setDslSchema(dslJson);
        pageSchema.setStatus(StatusConstants.PUBLISHED);
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);
        // status already set to PUBLISHED above

        return pageSchema;
    }

    @Override
    public PageSchema generateFormPage(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config
    ) {
        // Load dictionary bindings for fields
        Map<Long, String> dictBindings = loadDictBindings(fields);

        // Build UnifiedSchema JSON
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("kind", "Form");
        schema.put("version", "1.0.0");
        schema.put("id", "form." + model.getCode());
        schema.put("modelCode", model.getCode());
        // Title/description resolved by frontend: page.form.{modelCode}.title

        // Build state binding
        Map<String, String> stateBinding = new LinkedHashMap<>();
        stateBinding.put("model", "{{state.form}}");
        schema.put("stateBinding", stateBinding);

        // Build layout
        schema.put("layout", buildFormLayout());

        // Build areas
        Map<String, Object> areas = new LinkedHashMap<>();
        areas.put("main", buildFormMainArea(model, bindings, fields, config, dictBindings));
        schema.put("areas", areas);

        // Build dataSources
        schema.put("dataSources", buildFormDataSources(model, bindings, fields));

        // Build handlers
        schema.put("handlers", buildFormHandlers(model));

        // Convert to JSON string
        String dslJson;
        try {
            dslJson = objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            log.error("Failed to generate form page DSL for model: {}", model.getCode(), e);
            throw new BusinessException("Failed to generate form page DSL", e);
        }

        // Create PageSchema entity
        PageSchema pageSchema = new PageSchema();
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setName(model.getCode() + "_form");
        pageSchema.setTitle(model.getDisplayName() + "表单");
        pageSchema.setPageType("form");
        pageSchema.setDslSchema(dslJson);
        pageSchema.setStatus(StatusConstants.PUBLISHED);
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);
        // status already set to PUBLISHED above

        return pageSchema;
    }

    @Override
    public PageSchema generateDetailPage(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields,
            CrudTemplateConfig config
    ) {
        // Load dictionary bindings for fields
        Map<Long, String> dictBindings = loadDictBindings(fields);

        // Build UnifiedSchema JSON
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("kind", "Detail");
        schema.put("version", "1.0.0");
        schema.put("id", "detail." + model.getCode());
        schema.put("modelCode", model.getCode());
        // Title resolved by frontend: page.detail.{modelCode}.title

        // Build state binding
        Map<String, String> stateBinding = new LinkedHashMap<>();
        stateBinding.put("data", "{{state.detail}}");
        schema.put("stateBinding", stateBinding);

        // Build layout
        schema.put("layout", buildDetailLayout());

        // Build areas
        Map<String, Object> areas = new LinkedHashMap<>();
        areas.put("main", buildDetailMainArea(model, bindings, fields, config, dictBindings));
        schema.put("areas", areas);

        // Build dataSources
        schema.put("dataSources", buildDetailDataSources(model));

        // Build handlers
        schema.put("handlers", buildDetailHandlers(model));

        // Convert to JSON string
        String dslJson;
        try {
            dslJson = objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            log.error("Failed to generate detail page DSL for model: {}", model.getCode(), e);
            throw new BusinessException("Failed to generate detail page DSL", e);
        }

        // Create PageSchema entity
        PageSchema pageSchema = new PageSchema();
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setName(model.getCode() + "_detail");
        pageSchema.setTitle(model.getDisplayName() + "详情");
        pageSchema.setPageType("detail");
        pageSchema.setDslSchema(dslJson);
        pageSchema.setStatus(StatusConstants.PUBLISHED);
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);
        // status already set to PUBLISHED above

        return pageSchema;
    }

    private Map<String, Object> buildListLayout() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("areas", Arrays.asList("filters", "toolbar", "main"));

        Map<String, Object> areasConfig = new LinkedHashMap<>();

        // Filters area config
        Map<String, Object> filtersConfig = new LinkedHashMap<>();
        filtersConfig.put("type", "grid");
        filtersConfig.put("cols", 12);
        filtersConfig.put("rowGap", 8);
        filtersConfig.put("colGap", 8);
        areasConfig.put("filters", filtersConfig);

        // Toolbar area config
        Map<String, Object> toolbarConfig = new LinkedHashMap<>();
        toolbarConfig.put("type", "flex");
        toolbarConfig.put("direction", "row");
        toolbarConfig.put("justify", "space-between");
        toolbarConfig.put("align", "center");
        areasConfig.put("toolbar", toolbarConfig);

        // Main area config
        Map<String, Object> mainConfig = new LinkedHashMap<>();
        mainConfig.put("type", "grid");
        mainConfig.put("cols", 12);
        mainConfig.put("rowGap", 0);
        mainConfig.put("colGap", 0);
        areasConfig.put("main", mainConfig);

        layout.put("areasConfig", areasConfig);
        return layout;
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
        filterBlock.put("blockType", "filter-form");

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
        String dataType = field.getDataType().toUpperCase();
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
        button.put("action", "create");
        // Label resolved by frontend: action.create.label
        button.put("primary", true);
        // 权限码格式: DYNAMIC.{modelCode}.create
        button.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".create')}");

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> onClick = new LinkedHashMap<>();
        onClick.put("handler", "openCreateForm");
        events.put("onClick", onClick);
        button.put("events", events);

        return button;
    }

    private Map<String, Object> buildExportButton(Model model) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("code", "export");
        button.put("action", "export");
        // Label resolved by frontend: action.export.label
        // 权限码格式: DYNAMIC.{modelCode}.export
        button.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".export')}");

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
        // 权限码格式: DYNAMIC.{modelCode}.import
        button.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".import')}");

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
        // 权限码格式: DYNAMIC.{modelCode}.delete
        button.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".delete')}");
        button.put("enableWhen", "${state.selectedIds.length > 0}");

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
        tableBlock.put("blockType", "data-table");

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
            // Display all visible fields
            for (ModelFieldBinding binding : bindings) {
                if (Boolean.TRUE.equals(binding.getVisible())) {
                    Field field = fields.stream()
                            .filter(f -> f.getId().equals(binding.getFieldId()))
                            .findFirst()
                            .orElse(null);

                    if (field != null) {
                        String dictCode = dictBindings.get(field.getId());
                        columns.add(buildTableColumn(model, field, dictCode));
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
        viewBtn.put("action", "view");
        // Label resolved by frontend: action.view.label
        Map<String, Object> viewEvents = new LinkedHashMap<>();
        Map<String, Object> viewOnClick = new LinkedHashMap<>();
        viewOnClick.put("handler", "openDetailDrawer");
        Map<String, Object> viewArgs = new LinkedHashMap<>();
        viewArgs.put("id", "${row.id}");
        viewOnClick.put("args", viewArgs);
        viewEvents.put("onClick", viewOnClick);
        viewBtn.put("events", viewEvents);
        buttons.add(viewBtn);

        // Edit button
        Map<String, Object> editBtn = new LinkedHashMap<>();
        editBtn.put("code", "edit");
        editBtn.put("action", "update");
        // Label resolved by frontend: action.update
        // 权限码格式: DYNAMIC.{modelCode}.update
        editBtn.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".update')}");
        Map<String, Object> editEvents = new LinkedHashMap<>();
        Map<String, Object> editOnClick = new LinkedHashMap<>();
        editOnClick.put("handler", "openEditForm");
        Map<String, Object> editArgs = new LinkedHashMap<>();
        editArgs.put("id", "${row.id}");
        editOnClick.put("args", editArgs);
        editEvents.put("onClick", editOnClick);
        editBtn.put("events", editEvents);
        buttons.add(editBtn);

        // Delete button
        Map<String, Object> deleteBtn = new LinkedHashMap<>();
        deleteBtn.put("code", "delete");
        deleteBtn.put("action", "delete");
        // Label resolved by frontend: action.delete.label
        deleteBtn.put("danger", true);
        // 权限码格式: DYNAMIC.{modelCode}.delete
        deleteBtn.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".delete')}");
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

    private Map<String, Object> buildListDataSources(Model model) {
        Map<String, Object> dataSources = new LinkedHashMap<>();

        // Main list data source
        Map<String, Object> listDs = new LinkedHashMap<>();
        listDs.put("type", "api");
        listDs.put("endpoint", "/api/dynamic/" + model.getCode() + "/list");
        listDs.put("method", "get");
        listDs.put("params", "{{state.filters}}");
        listDs.put("pagination", true);
        listDs.put("autoFetch", true);
        listDs.put("adaptor", "table");

        dataSources.put("ds_" + model.getCode() + "List", listDs);

        return dataSources;
    }

    private Map<String, Object> buildListHandlers(Model model) {
        Map<String, Object> handlers = new LinkedHashMap<>();

        // Search handler
        Map<String, Object> searchHandler = new LinkedHashMap<>();
        searchHandler.put("type", "flow");
        List<Map<String, Object>> searchSteps = new ArrayList<>();
        Map<String, Object> searchStep = new LinkedHashMap<>();
        searchStep.put("action", "dataSource.fetch");
        searchStep.put("target", "ds_" + model.getCode() + "List");
        searchSteps.add(searchStep);
        searchHandler.put("steps", searchSteps);
        handlers.put("search", searchHandler);

        // Reset handler
        Map<String, Object> resetHandler = new LinkedHashMap<>();
        resetHandler.put("type", "flow");
        List<Map<String, Object>> resetSteps = new ArrayList<>();
        Map<String, Object> resetStep1 = new LinkedHashMap<>();
        resetStep1.put("action", "state.reset");
        resetStep1.put("target", "state.filters");
        resetSteps.add(resetStep1);
        Map<String, Object> resetStep2 = new LinkedHashMap<>();
        resetStep2.put("action", "dataSource.fetch");
        resetStep2.put("target", "ds_" + model.getCode() + "List");
        resetSteps.add(resetStep2);
        resetHandler.put("steps", resetSteps);
        handlers.put("reset", resetHandler);

        // Open create form handler
        Map<String, Object> createHandler = new LinkedHashMap<>();
        createHandler.put("type", "flow");
        List<Map<String, Object>> createSteps = new ArrayList<>();
        Map<String, Object> createStep = new LinkedHashMap<>();
        createStep.put("action", "ui.openContainer");
        createStep.put("target", "drawer.form");
        Map<String, Object> createArgs = new LinkedHashMap<>();
        createArgs.put("mode", "create");
        createStep.put("args", createArgs);
        createSteps.add(createStep);
        createHandler.put("steps", createSteps);
        handlers.put("openCreateForm", createHandler);

        // Open edit form handler
        Map<String, Object> editHandler = new LinkedHashMap<>();
        editHandler.put("type", "flow");
        List<Map<String, Object>> editSteps = new ArrayList<>();
        Map<String, Object> editStep1 = new LinkedHashMap<>();
        editStep1.put("action", "state.set");
        editSteps.add(editStep1);
        Map<String, Object> editStep2 = new LinkedHashMap<>();
        editStep2.put("action", "ui.openContainer");
        editStep2.put("target", "drawer.form");
        Map<String, Object> editArgs = new LinkedHashMap<>();
        editArgs.put("mode", "edit");
        editArgs.put("id", "${args.id}");
        editStep2.put("args", editArgs);
        editSteps.add(editStep2);
        editHandler.put("steps", editSteps);
        handlers.put("openEditForm", editHandler);

        // Open detail drawer handler
        Map<String, Object> detailHandler = new LinkedHashMap<>();
        detailHandler.put("type", "flow");
        List<Map<String, Object>> detailSteps = new ArrayList<>();
        Map<String, Object> detailStep1 = new LinkedHashMap<>();
        detailStep1.put("action", "state.set");
        detailSteps.add(detailStep1);
        Map<String, Object> detailStep2 = new LinkedHashMap<>();
        detailStep2.put("action", "ui.openContainer");
        detailStep2.put("target", "drawer.detail");
        Map<String, Object> detailArgs = new LinkedHashMap<>();
        detailArgs.put("id", "${args.id}");
        detailStep2.put("args", detailArgs);
        detailSteps.add(detailStep2);
        detailHandler.put("steps", detailSteps);
        handlers.put("openDetailDrawer", detailHandler);

        // Delete selected handler
        handlers.put("deleteSelected", buildDeleteSelectedHandler(model));

        // Delete single handler
        handlers.put("deleteSingle", buildDeleteSingleHandler(model));

        return handlers;
    }

    private Map<String, Object> buildDeleteSelectedHandler(Model model) {
        Map<String, Object> handler = new LinkedHashMap<>();
        handler.put("type", "flow");
        List<Map<String, Object>> steps = new ArrayList<>();

        // Confirm step
        Map<String, Object> confirmStep = new LinkedHashMap<>();
        confirmStep.put("action", "dialog.confirm");
        Map<String, Object> confirmArgs = new LinkedHashMap<>();
        confirmArgs.put("messageKey", "delete.confirm");
        // Title/content resolved by frontend: message.delete.confirm.title / message.delete.confirm.content
        confirmStep.put("args", confirmArgs);
        confirmStep.put("next", "doDelete");
        steps.add(confirmStep);

        // Delete step
        Map<String, Object> deleteStep = new LinkedHashMap<>();
        deleteStep.put("id", "doDelete");
        deleteStep.put("action", "api.request");
        deleteStep.put("method", "post");
        // 后端 API 格式: DELETE /api/dynamic/{modelCode}/batch
        deleteStep.put("endpoint", "/api/dynamic/" + model.getCode() + "/batch");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", "{{state.selectedIds}}");
        deleteStep.put("body", body);
        deleteStep.put("next", "afterDelete");
        steps.add(deleteStep);

        // Success toast step
        Map<String, Object> toastStep = new LinkedHashMap<>();
        toastStep.put("id", "afterDelete");
        toastStep.put("action", "toast.show");
        toastStep.put("level", "success");
        toastStep.put("messageKey", "delete.success");
        // Content resolved by frontend: message.delete.success
        steps.add(toastStep);

        // Refresh step
        Map<String, Object> refreshStep = new LinkedHashMap<>();
        refreshStep.put("action", "dataSource.fetch");
        refreshStep.put("target", "ds_" + model.getCode() + "List");
        steps.add(refreshStep);

        handler.put("steps", steps);
        return handler;
    }

    private Map<String, Object> buildDeleteSingleHandler(Model model) {
        Map<String, Object> handler = new LinkedHashMap<>();
        handler.put("type", "flow");
        List<Map<String, Object>> steps = new ArrayList<>();

        // Confirm step
        Map<String, Object> confirmStep = new LinkedHashMap<>();
        confirmStep.put("action", "dialog.confirm");
        Map<String, Object> confirmArgs = new LinkedHashMap<>();
        confirmArgs.put("messageKey", "delete.confirm");
        // Title/content resolved by frontend: message.delete.confirm.title / message.delete.confirm.content
        confirmStep.put("args", confirmArgs);
        confirmStep.put("next", "doDelete");
        steps.add(confirmStep);

        // Delete step
        Map<String, Object> deleteStep = new LinkedHashMap<>();
        deleteStep.put("id", "doDelete");
        deleteStep.put("action", "api.request");
        deleteStep.put("method", "delete");
        deleteStep.put("endpoint", "/api/dynamic/" + model.getCode() + "/${args.id}");
        steps.add(deleteStep);

        // Success toast step
        Map<String, Object> toastStep = new LinkedHashMap<>();
        toastStep.put("action", "toast.show");
        toastStep.put("level", "success");
        toastStep.put("messageKey", "delete.success");
        // Content resolved by frontend: message.delete.success
        steps.add(toastStep);

        // Refresh step
        Map<String, Object> refreshStep = new LinkedHashMap<>();
        refreshStep.put("action", "dataSource.fetch");
        refreshStep.put("target", "ds_" + model.getCode() + "List");
        steps.add(refreshStep);

        handler.put("steps", steps);
        return handler;
    }

    private Map<String, Object> buildFormLayout() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("areas", Collections.singletonList("main"));

        Map<String, Object> areasConfig = new LinkedHashMap<>();
        Map<String, Object> mainConfig = new LinkedHashMap<>();
        mainConfig.put("type", "grid");
        mainConfig.put("cols", 12);
        mainConfig.put("rowGap", 12);
        mainConfig.put("colGap", 12);
        areasConfig.put("main", mainConfig);

        layout.put("areasConfig", areasConfig);
        return layout;
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

        blocks.add(formSection);

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
                if (Boolean.TRUE.equals(binding.getEditable())) {
                    Field field = fields.stream()
                            .filter(f -> f.getId().equals(binding.getFieldId()))
                            .findFirst()
                            .orElse(null);

                    if (field != null) {
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

                if (field != null) {
                    ModelFieldBinding binding = bindings.stream()
                            .filter(b -> b.getFieldId().equals(field.getId()))
                            .findFirst()
                            .orElse(null);

                    if (binding != null) {
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
        formField.put("component", fieldTypeMapper.getFormComponent(field));

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 6);
        layout.put("rowSpan", 1);
        formField.put("layout", layout);

        // Add dictCode if field has dictionary binding
        if (dictCode != null && !dictCode.isEmpty()) {
            formField.put("dictCode", dictCode);
        }

        // Add props (only non-i18n props)
        Map<String, Object> props = new LinkedHashMap<>();
        // Placeholder resolved by frontend: model.{modelCode}.{fieldCode}.placeholder

        // Add number type for number fields
        if (fieldTypeMapper.isNumberField(field)) {
            props.put("type", "number");
        }

        if (!props.isEmpty()) {
            formField.put("props", props);
        }

        // Add validation rules
        if (Boolean.TRUE.equals(binding.getRequired())) {
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
        // Label resolved by frontend: action.save.label
        submitBtn.put("primary", true);
        // 权限码格式: DYNAMIC.{modelCode}.create/update
        submitBtn.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".create') || hasPermission('DYNAMIC." + model.getCode() + ".update')}");
        Map<String, Object> submitEvents = new LinkedHashMap<>();
        Map<String, Object> submitOnClick = new LinkedHashMap<>();
        submitOnClick.put("handler", "submitForm");
        submitEvents.put("onClick", submitOnClick);
        submitBtn.put("events", submitEvents);
        buttons.add(submitBtn);

        // Reset button
        Map<String, Object> resetBtn = new LinkedHashMap<>();
        resetBtn.put("code", "reset");
        resetBtn.put("action", "reset");
        // Label resolved by frontend: action.reset.label
        Map<String, Object> resetEvents = new LinkedHashMap<>();
        Map<String, Object> resetOnClick = new LinkedHashMap<>();
        resetOnClick.put("handler", "resetForm");
        resetEvents.put("onClick", resetOnClick);
        resetBtn.put("events", resetEvents);
        buttons.add(resetBtn);

        // Cancel button
        Map<String, Object> cancelBtn = new LinkedHashMap<>();
        cancelBtn.put("code", "cancel");
        cancelBtn.put("action", "cancel");
        // Label resolved by frontend: action.cancel.label
        Map<String, Object> cancelEvents = new LinkedHashMap<>();
        Map<String, Object> cancelOnClick = new LinkedHashMap<>();
        cancelOnClick.put("handler", "cancelForm");
        cancelEvents.put("onClick", cancelOnClick);
        cancelBtn.put("events", cancelEvents);
        buttons.add(cancelBtn);

        buttonsBlock.put("buttons", buttons);
        return buttonsBlock;
    }

    private Map<String, Object> buildFormDataSources(
            Model model,
            List<ModelFieldBinding> bindings,
            List<Field> fields
    ) {
        Map<String, Object> dataSources = new LinkedHashMap<>();

        // Add data sources for enum/reference fields
        for (ModelFieldBinding binding : bindings) {
            Field field = fields.stream()
                    .filter(f -> f.getId().equals(binding.getFieldId()))
                    .findFirst()
                    .orElse(null);

            if (field != null && ("enum".equals(field.getDataType()) || "reference".equals(field.getDataType()))) {
                Map<String, Object> ds = new LinkedHashMap<>();
                ds.put("valueField", "value");
                ds.put("labelField", "label");
                dataSources.put("ds_" + field.getCode(), ds);
            }
        }

        return dataSources;
    }

    private Map<String, Object> buildFormHandlers(Model model) {
        Map<String, Object> handlers = new LinkedHashMap<>();

        // Submit form handler
        Map<String, Object> submitHandler = new LinkedHashMap<>();
        submitHandler.put("type", "flow");
        List<Map<String, Object>> submitSteps = new ArrayList<>();

        Map<String, Object> validateStep = new LinkedHashMap<>();
        validateStep.put("action", "form.validate");
        validateStep.put("target", "form." + model.getCode());
        submitSteps.add(validateStep);

        Map<String, Object> apiStep = new LinkedHashMap<>();
        apiStep.put("action", "api.request");
        apiStep.put("method", "post");
        // 后端 API 格式: /api/dynamic/{modelCode}/create
        apiStep.put("endpoint", "/api/dynamic/" + model.getCode() + "/create");
        // 使用 {{form}} 而非 {{state.form}}，因为 ExpressionContext 中 form 是顶层属性
        apiStep.put("body", "{{form}}");
        apiStep.put("next", "afterSubmit");
        submitSteps.add(apiStep);

        Map<String, Object> toastStep = new LinkedHashMap<>();
        toastStep.put("id", "afterSubmit");
        toastStep.put("action", "toast.show");
        toastStep.put("level", "success");
        toastStep.put("messageKey", "save.success");
        // Content resolved by frontend: message.save.success
        submitSteps.add(toastStep);

        // Navigate back to list page after successful submission
        Map<String, Object> backStep = new LinkedHashMap<>();
        backStep.put("action", "router.back");
        submitSteps.add(backStep);

        submitHandler.put("steps", submitSteps);
        handlers.put("submitForm", submitHandler);

        // Reset form handler
        Map<String, Object> resetHandler = new LinkedHashMap<>();
        resetHandler.put("type", "flow");
        List<Map<String, Object>> resetSteps = new ArrayList<>();
        Map<String, Object> resetStep = new LinkedHashMap<>();
        resetStep.put("action", "form.reset");
        resetStep.put("target", "form." + model.getCode());
        resetSteps.add(resetStep);
        resetHandler.put("steps", resetSteps);
        handlers.put("resetForm", resetHandler);

        // Cancel form handler
        Map<String, Object> cancelHandler = new LinkedHashMap<>();
        cancelHandler.put("type", "flow");
        List<Map<String, Object>> cancelSteps = new ArrayList<>();
        Map<String, Object> cancelStep = new LinkedHashMap<>();
        cancelStep.put("action", "ui.closeContainer");
        cancelStep.put("target", "drawer.form");
        cancelSteps.add(cancelStep);
        cancelHandler.put("steps", cancelSteps);
        handlers.put("cancelForm", cancelHandler);

        return handlers;
    }


    private Map<String, Object> buildDetailLayout() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("areas", Collections.singletonList("main"));

        Map<String, Object> areasConfig = new LinkedHashMap<>();
        Map<String, Object> mainConfig = new LinkedHashMap<>();
        mainConfig.put("type", "grid");
        mainConfig.put("cols", 12);
        mainConfig.put("rowGap", 12);
        mainConfig.put("colGap", 12);
        areasConfig.put("main", mainConfig);

        layout.put("areasConfig", areasConfig);
        return layout;
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
        layout.put("colSpan", 6);
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
        buttonsBlock.put("blockType", "form-buttons");

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("colSpan", 12);
        layout.put("rowSpan", 1);
        buttonsBlock.put("layout", layout);

        List<Map<String, Object>> buttons = new ArrayList<>();

        // Edit button
        Map<String, Object> editBtn = new LinkedHashMap<>();
        editBtn.put("code", "edit");
        editBtn.put("action", "update");
        // Label resolved by frontend: action.update
        editBtn.put("primary", true);
        // 权限码格式: DYNAMIC.{modelCode}.update
        editBtn.put("visibleWhen", "${hasPermission('DYNAMIC." + model.getCode() + ".update')}");
        Map<String, Object> editEvents = new LinkedHashMap<>();
        Map<String, Object> editOnClick = new LinkedHashMap<>();
        editOnClick.put("handler", "openEditForm");
        editEvents.put("onClick", editOnClick);
        editBtn.put("events", editEvents);
        buttons.add(editBtn);

        // Back button
        Map<String, Object> backBtn = new LinkedHashMap<>();
        backBtn.put("code", "back");
        backBtn.put("action", "back");
        // Label resolved by frontend: action.back
        Map<String, Object> backEvents = new LinkedHashMap<>();
        Map<String, Object> backOnClick = new LinkedHashMap<>();
        backOnClick.put("handler", "goBack");
        backEvents.put("onClick", backOnClick);
        backBtn.put("events", backEvents);
        buttons.add(backBtn);

        buttonsBlock.put("buttons", buttons);
        return buttonsBlock;
    }

    private Map<String, Object> buildDetailDataSources(Model model) {
        Map<String, Object> dataSources = new LinkedHashMap<>();

        // Detail data source
        Map<String, Object> detailDs = new LinkedHashMap<>();
        detailDs.put("type", "api");
        detailDs.put("endpoint", "/api/dynamic/" + model.getCode() + "/${state.id}");
        detailDs.put("method", "get");
        detailDs.put("autoFetch", true);

        dataSources.put("ds_" + model.getCode() + "Detail", detailDs);

        return dataSources;
    }

    private Map<String, Object> buildDetailHandlers(Model model) {
        Map<String, Object> handlers = new LinkedHashMap<>();

        // Open edit form handler
        Map<String, Object> editHandler = new LinkedHashMap<>();
        editHandler.put("type", "flow");
        List<Map<String, Object>> editSteps = new ArrayList<>();
        Map<String, Object> editStep = new LinkedHashMap<>();
        editStep.put("action", "ui.openContainer");
        editStep.put("target", "drawer.form");
        Map<String, Object> editArgs = new LinkedHashMap<>();
        editArgs.put("mode", "edit");
        editArgs.put("id", "${state.id}");
        editStep.put("args", editArgs);
        editSteps.add(editStep);
        editHandler.put("steps", editSteps);
        handlers.put("openEditForm", editHandler);

        // Go back handler
        Map<String, Object> backHandler = new LinkedHashMap<>();
        backHandler.put("type", "flow");
        List<Map<String, Object>> backSteps = new ArrayList<>();
        Map<String, Object> backStep = new LinkedHashMap<>();
        backStep.put("action", "ui.closeContainer");
        backStep.put("target", "drawer.detail");
        backSteps.add(backStep);
        backHandler.put("steps", backSteps);
        handlers.put("goBack", backHandler);

        return handlers;
    }

}
