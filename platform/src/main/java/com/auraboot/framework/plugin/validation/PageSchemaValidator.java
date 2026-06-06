package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.validator.PageSchemaDslI18nValidator;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;

/**
 * S-PAGE: Validates page schema definitions within the plugin (V2 flat format).
 * <p>
 * Checks:
 * - Required field: kind (on DTO directly)
 * - kind is a recognized value
 * - blocks list is present and non-empty
 * - Block types are recognized
 * - I18n compliance: no hardcoded non-ASCII text in user-facing fields (S-PAGE-I18N)
 */
@Slf4j
@Component
public class PageSchemaValidator implements PluginValidator {

    // Import accepts only the three production-renderable page kinds. dashboard/composite
    // are valid PageKind values for the V3 designer but have no plugin-page renderer, so
    // they are rejected at import time (DSL V4 Phase B). This validator is import-only.
    private static final Set<String> VALID_KINDS = Set.of("list", "form", "detail");
    private static final Set<String> VALID_LAYOUT_TYPES = Set.of("grid", "stack");
    private static final Set<String> KNOWN_BLOCK_TYPES = DslRegistry.BlockType.codes();
    private static final Set<String> FORBIDDEN_LEGACY_TOP_LEVEL_FIELDS = Set.of("dslSchema", "pageType");
    private static final Set<String> FORM_FIELD_BLOCK_TYPES = Set.of("form-section");
    private static final Set<String> BUTTON_BLOCK_TYPES = Set.of("toolbar", "form-buttons");
    private static final Set<String> BUTTON_LIST_FIELDS = Set.of("buttons", "actions");
    private static final Set<String> SYSTEM_FIELD_CODES = Set.of(
            "pid", "id", "tenant_id", "created_at", "updated_at", "created_by", "updated_by",
            "createdAt", "updatedAt", "createdBy", "updatedBy");

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        if (manifest.getPages() == null) return messages;

        PageModelIndex modelIndex = buildModelIndex(manifest);

        for (int i = 0; i < manifest.getPages().size(); i++) {
            PageSchemaDTO page = manifest.getPages().get(i);
            if (page == null) continue;

            String path = "pages[" + i + "]";
            String pageKey = page.getPageKey() != null && !page.getPageKey().isBlank()
                    ? page.getPageKey()
                    : "<unknown>";

            // Validate kind field (required, on DTO directly)
            String kind = page.getKind();
            if (kind == null || kind.isBlank()) {
                messages.add(error("S-PAGE-KIND", category(), path + ".kind",
                        "Page '" + pageKey + "' is missing required field 'kind'. " +
                                "Page JSON must use the V2 flat format with top-level kind/layout/blocks."));
            } else if (!VALID_KINDS.contains(kind)) {
                messages.add(error("S-PAGE-KIND-UNKNOWN", category(), path + ".kind",
                        "Page '" + pageKey + "' has unsupported kind '" + kind + "'. " +
                                "Importable kinds: " + VALID_KINDS + " (dashboard/composite have no plugin-page renderer)."));
            }

            // schemaVersion must be explicitly declared as the current v4 page format.
            Integer schemaVersion = page.getSchemaVersion();
            if (schemaVersion == null || schemaVersion != DslRegistry.PAGE_SCHEMA_CURRENT_VERSION) {
                messages.add(error("S-PAGE-VERSION", category(), path + ".schemaVersion",
                        "Page '" + pageKey + "' must declare schemaVersion=" + DslRegistry.PAGE_SCHEMA_CURRENT_VERSION
                                + " (the current v4 page format) but was " + schemaVersion + ". "
                                + "Re-author/migrate the page to v4 before import."));
            }

            validateLegacyTopLevelFields(page, path, pageKey, messages);

            Map<String, Object> layout = page.getLayout();
            if (layout == null || layout.isEmpty()) {
                messages.add(error("S-PAGE-LAYOUT", category(), path + ".layout",
                        "Page '" + pageKey + "' is missing required top-level field 'layout'. " +
                                "Platform only accepts the latest V2 flat page format: kind/layout/blocks."));
            } else {
                Object layoutType = layout.get("type");
                if (layoutType == null || !VALID_LAYOUT_TYPES.contains(layoutType.toString())) {
                    messages.add(error("S-PAGE-LAYOUT-TYPE", category(), path + ".layout.type",
                            "Page '" + pageKey + "' has unsupported layout.type '" + layoutType + "'. " +
                                    "v4 pages must use layout.type 'grid' or 'stack'."));
                }
            }

            // Validate blocks (on DTO directly)
            List<Object> blocks = page.getBlocks();
            if (blocks == null || blocks.isEmpty()) {
                messages.add(error("S-PAGE-BLOCKS", category(), path + ".blocks",
                        "Page '" + pageKey + "' is missing required top-level field 'blocks' " +
                                "or the array is empty. Platform only accepts the latest V2 flat page format."));
            } else {
                int cols = (layout != null && layout.get("cols") instanceof Number colsNum)
                        ? colsNum.intValue() : 12;
                for (int j = 0; j < blocks.size(); j++) {
                    if (blocks.get(j) instanceof Map<?, ?> block) {
                        Object blockType = block.get("blockType");
                        if (blockType != null && !KNOWN_BLOCK_TYPES.contains(blockType.toString())) {
                            messages.add(error("S-PAGE-BLOCK-TYPE", category(),
                                    path + ".blocks[" + j + "].blockType",
                                    "Page '" + pageKey + "' has unknown blockType: '" +
                                            blockType + "'"));
                        }
                        // v4 grid blocks must fit within the page grid width (col + colSpan <= cols).
                        if (block.get("layout") instanceof Map<?, ?> bl && bl.get("col") instanceof Number colNum) {
                            int col = colNum.intValue();
                            int span = bl.get("colSpan") instanceof Number spanNum ? spanNum.intValue() : 0;
                            if (col >= cols || (col + span) > cols) {
                                messages.add(error("S-PAGE-BLOCK-COL", category(),
                                        path + ".blocks[" + j + "].layout.col",
                                        "Page '" + pageKey + "' block #" + j + " has col=" + col + " colSpan=" + span
                                                + " exceeding grid width cols=" + cols
                                                + ". v4 grid blocks must satisfy col + colSpan <= cols."));
                            }
                        }
                        validateBlockContract(page, path, pageKey, j, (Map<String, Object>) block,
                                modelIndex, messages);
                    }
                }
            }

            // I18n compliance: scan page title and all block text fields
            validateI18nCompliance(page, path, messages);
        }

        return messages;
    }

    private void validateBlockContract(PageSchemaDTO page,
                                       String pagePath,
                                       String pageKey,
                                       int blockIndex,
                                       Map<String, Object> block,
                                       PageModelIndex modelIndex,
                                       List<PluginValidationMessage> messages) {
        String blockPath = pagePath + ".blocks[" + blockIndex + "]";

        String blockId = stringValue(block.get("id"));
        if (isBlank(blockId)) {
            messages.add(error("S-PAGE-BLOCK-ID", category(), blockPath + ".id",
                    "Page '" + pageKey + "' has a block without required field 'id'. " +
                            "Every page block must have a stable id for golden verification and runtime diagnostics."));
        }

        String blockType = stringValue(block.get("blockType"));
        if (isBlank(blockType) || !KNOWN_BLOCK_TYPES.contains(blockType)) {
            return;
        }

        String effectiveModelCode = firstNonBlank(
                stringValue(block.get("modelCode")),
                stringValue(block.get("childModel")),
                page.getModelCode()
        );

        if ("table".equals(blockType)) {
            validateTableBlock(pageKey, blockPath, block, effectiveModelCode, modelIndex, messages);
            return;
        }

        if ("sub-table".equals(blockType)) {
            validateSubTableBlock(pageKey, blockPath, block, effectiveModelCode, modelIndex, messages);
            return;
        }

        if ("embedded-list".equals(blockType)) {
            // Embedded list carries a flat table contract (columns at block root,
            // bound to its own modelCode/childModel). Validate columns and dict
            // projection exactly like a table block so cells never leak raw values.
            validateTableBlock(pageKey, blockPath, block, effectiveModelCode, modelIndex, messages);
            return;
        }

        if (FORM_FIELD_BLOCK_TYPES.contains(blockType)) {
            validateFormSectionBlock(pageKey, blockPath, block, page.getKind(), effectiveModelCode, modelIndex, messages);
            return;
        }

        if (BUTTON_BLOCK_TYPES.contains(blockType)) {
            validateButtonBlock(pageKey, blockPath, block, messages);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateSubTableBlock(String pageKey,
                                       String blockPath,
                                       Map<String, Object> block,
                                       String modelCode,
                                       PageModelIndex modelIndex,
                                       List<PluginValidationMessage> messages) {
        Object subTableObj = block.get("subTable");
        if (subTableObj instanceof Map<?, ?> subTableRaw) {
            Map<String, Object> subTable = (Map<String, Object>) subTableRaw;
            String subTableModelCode = firstNonBlank(
                    stringValue(subTable.get("modelCode")),
                    stringValue(subTable.get("childModel")),
                    modelCode
            );
            validateTableBlock(pageKey, blockPath + ".subTable", subTable, subTableModelCode, modelIndex, messages);
            validateOptionalButtonLists(pageKey, blockPath + ".subTable", subTable, messages);
            return;
        }

        validateTableBlock(pageKey, blockPath, block, modelCode, modelIndex, messages);
    }

    @SuppressWarnings("unchecked")
    private void validateTableBlock(String pageKey,
                                    String blockPath,
                                    Map<String, Object> block,
                                    String modelCode,
                                    PageModelIndex modelIndex,
                                    List<PluginValidationMessage> messages) {
        TableContract tableContract = tableContract(blockPath, block);
        boolean modelBoundTable = !usesExternalDataSource(block) && !usesExternalDataSource(tableContract.source());
        Object columnsObj = tableContract.source().get("columns");
        if (!(columnsObj instanceof List<?> columns) || columns.isEmpty()) {
            messages.add(error("S-PAGE-TABLE-COLUMNS", category(), tableContract.path() + ".columns",
                    "Page '" + pageKey + "' table block is missing non-empty 'columns'. " +
                            "List/detail table semantics must be explicit; screenshots cannot verify absent fields."));
            return;
        }

        for (int i = 0; i < columns.size(); i++) {
            if (!(columns.get(i) instanceof Map<?, ?> columnRaw)) {
                continue;
            }
            Map<String, Object> column = (Map<String, Object>) columnRaw;
            String columnPath = tableContract.path() + ".columns[" + i + "]";
            String fieldCode = stringValue(column.get("field"));
            validateColumnBusinessLabel(pageKey, columnPath, column, fieldCode, modelIndex, messages);
            if (modelBoundTable && !isActionColumn(column) && !isSystemField(fieldCode)) {
                fieldCode = validateFieldReference(pageKey, columnPath + ".field", column.get("field"),
                        modelCode, modelIndex, messages);
                validateColumnDictProjection(pageKey, columnPath, fieldCode, column, modelIndex, messages);
            }
            validateOptionalButtonLists(pageKey, columnPath, column, messages);
        }
    }

    private void validateColumnDictProjection(String pageKey,
                                              String columnPath,
                                              String fieldCode,
                                              Map<String, Object> column,
                                              PageModelIndex modelIndex,
                                              List<PluginValidationMessage> messages) {
        if (isBlank(fieldCode)) {
            return;
        }
        String expectedDictCode = modelIndex.dictCodeByField().get(fieldCode);
        if (isBlank(expectedDictCode)) {
            return;
        }
        String actualDictCode = stringValue(column.get("dictCode"));
        if (expectedDictCode.equals(actualDictCode)) {
            return;
        }
        messages.add(error("S-PAGE-TABLE-DICT", category(), columnPath + ".dictCode",
                "Page '" + pageKey + "' renders dictionary-backed field '" + fieldCode +
                        "' in a table column but does not declare matching dictCode '" + expectedDictCode +
                        "'. Without this, runtime table cells expose raw enum values instead of business labels."));
    }

    @SuppressWarnings("unchecked")
    private void validateFormSectionBlock(String pageKey,
                                          String blockPath,
                                          Map<String, Object> block,
                                          String pageKind,
                                          String modelCode,
                                          PageModelIndex modelIndex,
                                          List<PluginValidationMessage> messages) {
        Object fieldsObj = block.get("fields");
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty()) {
            messages.add(error("S-PAGE-FORM-FIELDS", category(), blockPath + ".fields",
                    "Page '" + pageKey + "' form-section block is missing non-empty 'fields'. " +
                            "Form pages must declare the actual editable fields."));
            return;
        }

        for (int i = 0; i < fields.size(); i++) {
            if (!(fields.get(i) instanceof Map<?, ?> fieldRaw)) {
                continue;
            }
            Map<String, Object> field = (Map<String, Object>) fieldRaw;
            String fieldPath = blockPath + ".fields[" + i + "]";
            String fieldCode = validateFieldReference(pageKey, fieldPath + ".field", field.get("field"),
                    modelCode, modelIndex, messages);
            validateRawLabelIfPresent(pageKey, fieldPath + ".label", field.get("label"), field, messages);
            if ("form".equals(pageKind)) {
                validateRequiredFieldProjection(pageKey, fieldPath, fieldCode, field, modelCode, modelIndex, messages);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateButtonBlock(String pageKey,
                                     String blockPath,
                                     Map<String, Object> block,
                                     List<PluginValidationMessage> messages) {
        boolean hasButtons = validateOptionalButtonLists(pageKey, blockPath, block, messages);

        if (!hasButtons) {
            messages.add(error("S-PAGE-BUTTONS", category(), blockPath + ".buttons",
                    "Page '" + pageKey + "' " + block.get("blockType") + " block is missing non-empty buttons/actions. " +
                            "Toolbar and form button blocks must declare visible commands."));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean validateOptionalButtonLists(String pageKey,
                                                String ownerPath,
                                                Map<String, Object> owner,
                                                List<PluginValidationMessage> messages) {
        boolean hasButtons = false;
        for (String buttonListField : BUTTON_LIST_FIELDS) {
            Object buttonsObj = owner.get(buttonListField);
            if (!(buttonsObj instanceof List<?> buttons) || buttons.isEmpty()) {
                continue;
            }
            hasButtons = true;
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i) instanceof Map<?, ?> buttonRaw) {
                    Map<String, Object> button = (Map<String, Object>) buttonRaw;
                    BusinessLabel buttonLabel = businessLabel(button);
                    validateBusinessLabel(pageKey, ownerPath + "." + buttonListField + "[" + i + "]." + buttonLabel.fieldName(),
                            buttonLabel.value(),
                            button,
                            true,
                            messages);
                }
            }
        }
        return hasButtons;
    }

    @SuppressWarnings("unchecked")
    private TableContract tableContract(String blockPath, Map<String, Object> block) {
        if (block.get("columns") instanceof List<?>) {
            return new TableContract(blockPath, block);
        }
        if (block.get("table") instanceof Map<?, ?> tableRaw) {
            return new TableContract(blockPath + ".table", (Map<String, Object>) tableRaw);
        }
        return new TableContract(blockPath, block);
    }

    @SuppressWarnings("unchecked")
    private boolean usesExternalDataSource(Map<String, Object> owner) {
        Object dataSource = owner.get("dataSource");
        if (dataSource instanceof String sourceId) {
            return !sourceId.isBlank();
        }
        if (dataSource instanceof Map<?, ?> dataSourceRaw) {
            Map<String, Object> dataSourceMap = (Map<String, Object>) dataSourceRaw;
            String type = stringValue(dataSourceMap.get("type"));
            if ("api".equals(type) || "namedQuery".equals(type)) {
                return true;
            }
            Object params = dataSourceMap.get("params");
            if (params instanceof Map<?, ?> paramsRaw) {
                String datasourceId = stringValue(((Map<String, Object>) paramsRaw).get("datasourceId"));
                return datasourceId != null && datasourceId.startsWith("nq:");
            }
        }
        return false;
    }

    private BusinessLabel businessLabel(Map<String, Object> owner) {
        Object label = owner.get("label");
        if (!isMissingLabel(label)) {
            return new BusinessLabel("label", label);
        }
        return new BusinessLabel("content", owner.get("content"));
    }

    private String validateFieldReference(String pageKey,
                                          String fieldPath,
                                          Object fieldValue,
                                          String modelCode,
                                          PageModelIndex modelIndex,
                                          List<PluginValidationMessage> messages) {
        String fieldCode = stringValue(fieldValue);
        if (isBlank(fieldCode)) {
            messages.add(error("S-PAGE-FIELD-REF", category(), fieldPath,
                    "Page '" + pageKey + "' is missing required field reference at '" + fieldPath + "'. " +
                            "Table columns and form fields must declare the model field they render."));
            return null;
        }

        Set<String> knownFields = modelIndex.fieldsByModel().get(modelCode);
        if (!isBlank(modelCode) && knownFields != null && !knownFields.contains(fieldCode)) {
            messages.add(error("S-PAGE-FIELD-REF", category(), fieldPath,
                    "Page '" + pageKey + "' references field '" + fieldCode + "' that is not bound to model '" +
                            modelCode + "'. Bind the field to the model or correct the page DSL field reference."));
        }
        return fieldCode;
    }

    private void validateRequiredFieldProjection(String pageKey,
                                                 String fieldPath,
                                                 String fieldCode,
                                                 Map<String, Object> field,
                                                 String modelCode,
                                                 PageModelIndex modelIndex,
                                                 List<PluginValidationMessage> messages) {
        if (isBlank(modelCode) || isBlank(fieldCode)) {
            return;
        }
        Set<String> requiredFields = modelIndex.requiredFieldsByModel().getOrDefault(modelCode, Collections.emptySet());
        if (!requiredFields.contains(fieldCode)) {
            return;
        }
        if (Boolean.TRUE.equals(field.get("readOnly")) || Boolean.TRUE.equals(field.get("hidden"))) {
            return;
        }
        if (!Boolean.TRUE.equals(field.get("required"))) {
            messages.add(error("S-PAGE-FORM-REQUIRED", category(), fieldPath + ".required",
                    "Page '" + pageKey + "' uses required model field '" + fieldCode + "' in an editable form " +
                            "but does not mark the page field as required. Required empty-submit validation must be field-level."));
        }
    }

    private void validateBusinessLabel(String pageKey,
                                       String labelPath,
                                       Object label,
                                       Map<String, Object> owner,
                                       boolean required,
                                       List<PluginValidationMessage> messages) {
        if (isMissingLabel(label)) {
            if (required) {
                messages.add(error("S-PAGE-LABEL", category(), labelPath,
                        "Page '" + pageKey + "' is missing a business label at '" + labelPath +
                                "'. User-visible list headers and command labels must not rely on field/action codes."));
            }
            return;
        }
        validateRawLabelIfPresent(pageKey, labelPath, label, owner, messages);
    }

    private void validateColumnBusinessLabel(String pageKey,
                                             String columnPath,
                                             Map<String, Object> column,
                                             String fieldCode,
                                             PageModelIndex modelIndex,
                                             List<PluginValidationMessage> messages) {
        Object label = column.get("label");
        if (!isMissingLabel(label)) {
            validateRawLabelIfPresent(pageKey, columnPath + ".label", label, column, messages);
            return;
        }

        if (!isActionColumn(column) && hasFieldBusinessLabel(fieldCode, modelIndex)) {
            return;
        }

        validateBusinessLabel(pageKey, columnPath + ".label", label, column, true, messages);
    }

    private void validateRawLabelIfPresent(String pageKey,
                                           String labelPath,
                                           Object label,
                                           Map<String, Object> owner,
                                           List<PluginValidationMessage> messages) {
        if (label instanceof String str && isRawCodeLabel(str, owner)) {
            messages.add(error("S-PAGE-LABEL", category(), labelPath,
                    "Page '" + pageKey + "' exposes raw code label '" + str + "'. " +
                            "Use LocalizedText or $i18n:key with business wording."));
        }
    }

    private boolean isMissingLabel(Object label) {
        if (label == null) {
            return true;
        }
        if (label instanceof String str) {
            return str.isBlank();
        }
        if (label instanceof Map<?, ?> map) {
            return map.isEmpty() || map.values().stream()
                    .noneMatch(value -> value instanceof String str && !str.isBlank());
        }
        return false;
    }

    private boolean isRawCodeLabel(String label, Map<String, Object> owner) {
        if (label == null || label.isBlank() || label.startsWith("$i18n:")) {
            return false;
        }
        if (label.contains("_") || label.contains(".")) {
            return true;
        }
        Set<String> rawCandidates = Set.of("field", "code", "action", "command", "commandCode", "status", "value").stream()
                .map(owner::get)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toSet());
        return rawCandidates.contains(label);
    }

    private boolean isActionColumn(Map<String, Object> column) {
        return Boolean.TRUE.equals(column.get("isActionColumn"))
                || "actions".equals(stringValue(column.get("field")))
                || "_actions".equals(stringValue(column.get("field")));
    }

    private PageModelIndex buildModelIndex(PluginManifestExtended manifest) {
        Map<String, Set<String>> fieldsByModel = new HashMap<>();
        Map<String, Set<String>> requiredFieldsByModel = new HashMap<>();
        Map<String, Set<String>> fieldLabelsByCode = fieldLabelsByCode(manifest);
        Map<String, String> dictCodeByField = fieldDictCodesByCode(manifest);
        Set<String> globallyRequiredFields = requiredFieldCodes(manifest);

        if (manifest.getModelFieldBindings() == null) {
            return new PageModelIndex(fieldsByModel, requiredFieldsByModel, fieldLabelsByCode, dictCodeByField);
        }

        for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
            if (binding == null || isBlank(binding.getModelCode()) || isBlank(binding.getFieldCode())) {
                continue;
            }
            fieldsByModel.computeIfAbsent(binding.getModelCode(), ignored -> new LinkedHashSet<>())
                    .add(binding.getFieldCode());
            if (Boolean.TRUE.equals(binding.getRequired()) || globallyRequiredFields.contains(binding.getFieldCode())) {
                requiredFieldsByModel.computeIfAbsent(binding.getModelCode(), ignored -> new LinkedHashSet<>())
                        .add(binding.getFieldCode());
            }
        }

        return new PageModelIndex(fieldsByModel, requiredFieldsByModel, fieldLabelsByCode, dictCodeByField);
    }

    private Map<String, Set<String>> fieldLabelsByCode(PluginManifestExtended manifest) {
        Map<String, Set<String>> labelsByCode = new HashMap<>();
        if (manifest.getFields() == null) {
            return labelsByCode;
        }
        for (FieldDefinitionDTO field : manifest.getFields()) {
            if (field == null || isBlank(field.getCode())) {
                continue;
            }
            addFieldLabel(labelsByCode, field.getCode(), field.getDisplayName());
            addFieldLabel(labelsByCode, field.getCode(), field.getDisplayNameZhCN());
            addFieldLabel(labelsByCode, field.getCode(), field.getDisplayNameEn());
        }
        return labelsByCode;
    }

    private Map<String, String> fieldDictCodesByCode(PluginManifestExtended manifest) {
        Map<String, String> dictCodesByCode = new HashMap<>();
        if (manifest.getFields() == null) {
            return dictCodesByCode;
        }
        for (FieldDefinitionDTO field : manifest.getFields()) {
            if (field == null || isBlank(field.getCode())) {
                continue;
            }
            String dictCode = firstNonBlank(
                    field.getDictCode(),
                    field.getExtension() == null ? null : stringValue(field.getExtension().get("dictCode"))
            );
            if (!isBlank(dictCode)) {
                dictCodesByCode.put(field.getCode(), dictCode);
            }
        }
        return dictCodesByCode;
    }

    private void addFieldLabel(Map<String, Set<String>> labelsByCode, String fieldCode, String label) {
        if (!isBlank(label)) {
            labelsByCode.computeIfAbsent(fieldCode, ignored -> new LinkedHashSet<>()).add(label);
        }
    }

    private boolean hasFieldBusinessLabel(String fieldCode, PageModelIndex modelIndex) {
        if (isBlank(fieldCode)) {
            return false;
        }
        if (isSystemField(fieldCode)) {
            return true;
        }
        Set<String> labels = modelIndex.fieldLabelsByCode().getOrDefault(fieldCode, Collections.emptySet());
        return labels.stream().anyMatch(label -> !isRawCodeText(label, fieldCode));
    }

    private boolean isSystemField(String fieldCode) {
        return fieldCode != null && SYSTEM_FIELD_CODES.contains(fieldCode);
    }

    private boolean isRawCodeText(String label, String code) {
        if (isBlank(label)) {
            return true;
        }
        if (label.equals(code)) {
            return true;
        }
        return label.contains("_") || label.contains(".");
    }

    private Set<String> requiredFieldCodes(PluginManifestExtended manifest) {
        if (manifest.getFields() == null) {
            return Collections.emptySet();
        }
        Set<String> requiredFields = new LinkedHashSet<>();
        for (FieldDefinitionDTO field : manifest.getFields()) {
            if (field == null || isBlank(field.getCode()) || field.getConstraints() == null) {
                continue;
            }
            if (Boolean.TRUE.equals(field.getConstraints().getRequired())) {
                requiredFields.add(field.getCode());
            }
        }
        return requiredFields;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PageModelIndex(Map<String, Set<String>> fieldsByModel,
                                  Map<String, Set<String>> requiredFieldsByModel,
                                  Map<String, Set<String>> fieldLabelsByCode,
                                  Map<String, String> dictCodeByField) {}

    private void validateLegacyTopLevelFields(PageSchemaDTO page, String path, String pageKey,
                                              List<PluginValidationMessage> messages) {
        Map<String, Object> unknownFields = page.getUnknownFields();
        if (unknownFields == null || unknownFields.isEmpty()) {
            return;
        }

        for (String legacyField : FORBIDDEN_LEGACY_TOP_LEVEL_FIELDS) {
            if (unknownFields.containsKey(legacyField)) {
                messages.add(error("S-PAGE-LEGACY-FORMAT", category(), path + "." + legacyField,
                        "Page '" + pageKey + "' uses deprecated top-level field '" + legacyField + "'. " +
                                "Platform only accepts the latest V2 flat page format with top-level " +
                                "kind/layout/blocks. Update the page JSON instead of relying on legacy DSL fields."));
            }
        }

        Set<String> unknownTopLevelFields = new LinkedHashSet<>(unknownFields.keySet());
        unknownTopLevelFields.removeAll(FORBIDDEN_LEGACY_TOP_LEVEL_FIELDS);
        if (!unknownTopLevelFields.isEmpty()) {
            messages.add(error("S-PAGE-UNKNOWN-FIELDS", category(), path,
                    "Page '" + pageKey + "' contains unsupported top-level fields: " + unknownTopLevelFields +
                            ". Platform only accepts the latest V2 flat page format with top-level " +
                            "kind/layout/blocks."));
        }
    }

    /**
     * Validate i18n compliance for user-facing text fields in the page and its blocks.
     * Any hardcoded non-ASCII string (e.g. Chinese) in title/label/placeholder etc. is
     * reported as an error with rule code S-PAGE-I18N.
     *
     * @param page     the page DTO to validate
     * @param basePath JSON path prefix for error messages
     * @param messages accumulator for validation messages
     */
    @SuppressWarnings("unchecked")
    private void validateI18nCompliance(PageSchemaDTO page, String basePath,
                                        List<PluginValidationMessage> messages) {
        String pageKey = page.getPageKey();

        // Check page-level title
        collectI18nViolations(basePath + ".title", page.getTitle(), pageKey, messages);

        // Check each block's text fields
        List<Object> blocks = page.getBlocks();
        if (blocks == null) return;

        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) instanceof Map<?, ?> blockMap) {
                String blockPath = basePath + ".blocks[" + j + "]";
                scanBlockForI18n(blockPath, (Map<String, Object>) blockMap, pageKey, messages);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scanBlockForI18n(String blockPath, Map<String, Object> blockMap,
                                  String pageKey, List<PluginValidationMessage> messages) {
        // Check all direct text fields on the block
        for (String field : PageSchemaDslI18nValidator.BLOCK_TEXT_FIELDS) {
            Object value = blockMap.get(field);
            if (value != null) {
                collectI18nViolations(blockPath + "." + field, value, pageKey, messages);
            }
        }

        // Recurse into sub-lists (columns, fields, actions, tabs, filters, …)
        for (String subList : PageSchemaDslI18nValidator.BLOCK_SUB_LISTS) {
            Object sub = blockMap.get(subList);
            if (sub instanceof List<?> items) {
                for (int k = 0; k < items.size(); k++) {
                    if (items.get(k) instanceof Map<?, ?> itemMap) {
                        scanBlockForI18n(blockPath + "." + subList + "[" + k + "]",
                                (Map<String, Object>) itemMap, pageKey, messages);
                    }
                }
            }
        }
    }

    private void collectI18nViolations(String path, Object value, String pageKey,
                                       List<PluginValidationMessage> messages) {
        List<PageSchemaDslI18nValidator.Violation> violations =
                PageSchemaDslI18nValidator.collectViolations(path, value);
        for (PageSchemaDslI18nValidator.Violation v : violations) {
            messages.add(error("S-PAGE-I18N", category(), v.path(),
                    "Page '" + pageKey + "': hardcoded non-ASCII text in DSL field '" +
                            v.path() + "'. Value: \"" + v.value() + "\". " +
                            "Use LocalizedText map or $i18n:key instead."));
        }
    }

    private record TableContract(String path, Map<String, Object> source) {}

    private record BusinessLabel(String fieldName, Object value) {}
}
