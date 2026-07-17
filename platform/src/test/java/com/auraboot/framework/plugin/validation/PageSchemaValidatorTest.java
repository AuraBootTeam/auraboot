package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PageSchemaValidatorTest {

    private final PageSchemaValidator validator = new PageSchemaValidator();

    @Test
    void tableBlockWithoutIdIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "blockType", "table",
                "columns", List.of(column("pe_order_no", localized("Order No")))
        )))));

        assertHasError(validate(manifest), "S-PAGE-BLOCK-ID", "pages[0].blocks[0].id");
    }

    @Test
    void tableBlockWithoutColumnsIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table"
        )))));

        assertHasError(validate(manifest), "S-PAGE-TABLE-COLUMNS", "pages[0].blocks[0].columns");
    }

    @Test
    void tableColumnWithoutBusinessLabelIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(Map.of("field", "pe_unlabeled"))
        )))));

        assertHasError(validate(manifest), "S-PAGE-LABEL", "pages[0].blocks[0].columns[0].label");
    }

    @Test
    void tableBlockCanDeclareColumnsUnderTableConfig() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "table", Map.of(
                        "columns", List.of(column("pe_order_no", localized("Order No")))
                )
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-TABLE-COLUMNS".equals(m.getCode())),
                () -> "Expected nested table.columns to pass but got " + messages);
    }

    @Test
    void tableColumnWithoutPageLabelCanUseFieldDisplayName() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(Map.of("field", "pe_order_no"))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-LABEL".equals(m.getCode())),
                () -> "Expected field displayName fallback to satisfy column label but got " + messages);
    }

    @Test
    void tableColumnUsingRawCodeAsLabelIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(column("pe_order_no", "pe_order_no"))
        )))));

        assertHasError(validate(manifest), "S-PAGE-LABEL", "pages[0].blocks[0].columns[0].label");
    }

    @Test
    void tableColumnReferencingUnboundFieldIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(column("pe_missing_field", localized("Missing Field")))
        )))));

        assertHasError(validate(manifest), "S-PAGE-FIELD-REF", "pages[0].blocks[0].columns[0].field");
    }

    @Test
    void systemTableColumnCanUsePlatformDisplayLabel() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(Map.of("field", "created_at"))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-LABEL".equals(m.getCode())
                        || "S-PAGE-FIELD-REF".equals(m.getCode())),
                () -> "Expected system column display policy to pass but got " + messages);
    }

    @Test
    void namedQueryTableColumnsAreNotRequiredToBindToPageModel() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "dataSource", Map.of("type", "namedQuery", "queryCode", "order_activity"),
                "columns", List.of(column("virtual_count", localized("Virtual Count")))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-FIELD-REF".equals(m.getCode())),
                () -> "Expected namedQuery columns to pass field binding validation but got " + messages);
    }

    @Test
    void apiBackedTableColumnsAreNotRequiredToBindToPageModel() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "dataSource", Map.of("type", "api", "url", "/api/orders/summary"),
                "columns", List.of(column("apiOnlyField", localized("API Only Field")))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-FIELD-REF".equals(m.getCode())),
                () -> "Expected API-backed columns to pass field binding validation but got " + messages);
    }

    @Test
    void namedPageDataSourceTableColumnsAreNotRequiredToBindToPageModel() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_workbench", "detail", "pe_order", List.of(Map.of(
                "id", "standard_lines",
                "blockType", "table",
                "dataSource", "standardLines",
                "table", Map.of(
                        "columns", List.of(column("bom_std_material_code", localized("Standard Code")))
                )
        )));
        p.setDataSources(Map.of(
                "standardLines", Map.of(
                        "type", "api",
                        "endpoint", "/api/dynamic/bom_standard_line_pcba/list"
                )
        ));
        manifest.setPages(List.of(p));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-FIELD-REF".equals(m.getCode())),
                () -> "Expected named page dataSource columns to pass field binding validation but got " + messages);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-UNKNOWN-FIELDS".equals(m.getCode())),
                () -> "Expected top-level dataSources to be a first-class page property but got " + messages);
    }

    @Test
    void topLevelDataSourcesDeserializeAsFirstClassPageProperty() throws Exception {
        PageSchemaDTO page = new ObjectMapper().readValue("""
                {
                  "pageKey": "pe_order_workbench",
                  "kind": "detail",
                  "schemaVersion": 4,
                  "modelCode": "pe_order",
                  "layout": { "type": "stack" },
                  "dataSources": {
                    "standardLines": {
                      "type": "api",
                      "endpoint": "/api/dynamic/bom_standard_line_pcba/list"
                    }
                  },
                  "blocks": [
                    {
                      "id": "standard_lines",
                      "blockType": "table",
                      "dataSource": "standardLines",
                      "columns": [
                        {
                          "field": "bom_std_material_code",
                          "label": { "en": "Standard Code", "zh-CN": "Standard Code" }
                        }
                      ]
                    }
                  ]
                }
                """, PageSchemaDTO.class);

        assertTrue(page.getDataSources() != null && page.getDataSources().containsKey("standardLines"),
                () -> "Expected top-level dataSources to bind to PageSchemaDTO but got " + page);
        assertTrue(page.getUnknownFields() == null || !page.getUnknownFields().containsKey("dataSources"),
                () -> "Expected dataSources not to be treated as an unknown field: " + page.getUnknownFields());
    }

    @Test
    void underscoreActionsColumnIsRecognizedAsActionColumn() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(Map.of(
                        "field", "_actions",
                        "label", localized("Actions"),
                        "buttons", List.of(Map.of(
                                "code", "view",
                                "label", localized("View")
                        ))
                ))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-FIELD-REF".equals(m.getCode())),
                () -> "Expected _actions to pass field binding validation but got " + messages);
    }

    @Test
    void tableColumnForDictionaryFieldWithoutDictCodeIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(column("pe_status", localized("Status")))
        )))));

        assertHasError(validate(manifest), "S-PAGE-TABLE-DICT", "pages[0].blocks[0].columns[0].dictCode");
    }

    @Test
    void tableColumnForDictionaryFieldWithMatchingDictCodeIsAccepted() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        Map<String, Object> statusColumn = column("pe_status", localized("Status"));
        statusColumn.put("dictCode", "pe_order_status");
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(statusColumn)
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-TABLE-DICT".equals(m.getCode())),
                () -> "Expected matching dictCode to pass but got " + messages);
    }

    @Test
    void tableColumnForSharedDictionaryFieldCanDeclarePageSpecificDictCode() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.getDicts().add(dict("pe_bpm_process_status"));
        Map<String, Object> statusColumn = column("pe_status", localized("Status"));
        statusColumn.put("dictCode", "pe_bpm_process_status");
        manifest.setPages(List.of(page("pe_bpm_process_list", "list", "pe_order", List.of(Map.of(
                "id", "process_table",
                "blockType", "table",
                "columns", List.of(statusColumn)
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-TABLE-DICT".equals(m.getCode())),
                () -> "Expected page-specific dictCode override to pass but got " + messages);
    }

    @Test
    void tableColumnWithoutFieldIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(Map.of("label", localized("Order No")))
        )))));

        assertHasError(validate(manifest), "S-PAGE-FIELD-REF", "pages[0].blocks[0].columns[0].field");
    }

    @Test
    void rowActionButtonUsingRawCodeAsLabelIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(Map.of(
                        "field", "actions",
                        "label", localized("Actions"),
                        "isActionColumn", true,
                        "buttons", List.of(Map.of(
                                "code", "view",
                                "label", "view"
                        ))
                ))
        )))));

        assertHasError(validate(manifest), "S-PAGE-LABEL", "pages[0].blocks[0].columns[0].buttons[0].label");
    }

    @Test
    void nestedSubTableColumnLabelIsValidated() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_detail", "detail", "pe_order", List.of(Map.of(
                "id", "order_lines",
                "blockType", "sub-table",
                "subTable", Map.of(
                        "childModel", "pe_order",
                        "columns", List.of(column("pe_order_no", "pe_order_no"))
                )
        )))));

        assertHasError(validate(manifest), "S-PAGE-LABEL", "pages[0].blocks[0].subTable.columns[0].label");
    }

    @Test
    void embeddedListBlockIsRecognizedBlockType() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_detail", "detail", "pe_order", List.of(Map.of(
                "id", "order_lines",
                "blockType", "embedded-list",
                "modelCode", "pe_order",
                "columns", List.of(column("pe_order_no", localized("Order No")))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-BLOCK-TYPE".equals(m.getCode())),
                () -> "embedded-list should be a recognized block type, got: " + messages);
    }

    @Test
    void workbenchBlocksAreRecognizedBlockTypes() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_workbench", "list", "pe_order", List.of(
                Map.of(
                        "id", "order_metrics",
                        "blockType", "metric-strip",
                        "dataSource", Map.of("type", "namedQuery", "queryCode", "pe_order_metrics"),
                        "metrics", List.of(Map.of("key", "pending", "label", localized("Pending"), "valueField", "pending_count"))
                ),
                Map.of(
                        "id", "order_inspector",
                        "blockType", "record-inspector",
                        "context", "${state.selectedOrder}",
                        "blocks", List.of(Map.of(
                                "id", "order_description",
                                "blockType", "description"
                        ))
                ),
                Map.of(
                        "id", "order_candidates",
                        "blockType", "candidate-list",
                        "dataSource", Map.of("type", "namedQuery", "queryCode", "pe_order_candidates"),
                        "item", Map.of("keyField", "pid", "titleField", "pe_order_no")
                ),
                Map.of(
                        "id", "order_evidence",
                        "blockType", "evidence-panel",
                        "context", "${state.selectedCandidate}",
                        "sections", List.of(Map.of(
                                "key", "evidence",
                                "label", localized("Evidence"),
                                "field", "evidence_json",
                                "format", "json"
                        ))
                ),
                Map.of(
                        "id", "order_artifacts",
                        "blockType", "artifact-timeline",
                        "dataSource", "exportRevisions",
                        "item", Map.of(
                                "keyField", "pid",
                                "titleField", "file_name",
                                "revisionField", "revision_no",
                                "fileIdField", "file_id"
                        )
                ),
                Map.of(
                        "id", "order_status",
                        "blockType", "status-banner",
                        "dataSource", "taskSummary",
                        "statusField", "status",
                        "hideStatuses", List.of("completed")
                )
        ))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-BLOCK-TYPE".equals(m.getCode())),
                () -> "Workbench block types should be recognized, got: " + messages);
    }

    @Test
    void embeddedListColumnWithoutFieldIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_detail", "detail", "pe_order", List.of(Map.of(
                "id", "order_lines",
                "blockType", "embedded-list",
                "modelCode", "pe_order",
                "columns", List.of(Map.of("label", localized("Order No")))
        )))));

        assertHasError(validate(manifest), "S-PAGE-FIELD-REF", "pages[0].blocks[0].columns[0].field");
    }

    @Test
    void embeddedListWithoutColumnsIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_detail", "detail", "pe_order", List.of(Map.of(
                "id", "order_lines",
                "blockType", "embedded-list",
                "modelCode", "pe_order"
        )))));

        assertHasError(validate(manifest), "S-PAGE-TABLE-COLUMNS", "pages[0].blocks[0].columns");
    }

    @Test
    void formSectionWithoutFieldsIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_form", "form", "pe_order", List.of(Map.of(
                "id", "basic_section",
                "blockType", "form-section"
        )))));

        assertHasError(validate(manifest), "S-PAGE-FORM-FIELDS", "pages[0].blocks[0].fields");
    }

    @Test
    void requiredModelFieldUsedInFormMustBeRequiredOnPage() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_form", "form", "pe_order", List.of(Map.of(
                "id", "basic_section",
                "blockType", "form-section",
                "fields", List.of(Map.of(
                        "field", "pe_order_no",
                        "component", "input"
                ))
        )))));

        assertHasError(validate(manifest), "S-PAGE-FORM-REQUIRED", "pages[0].blocks[0].fields[0].required");
    }

    @Test
    void formFieldWithoutFieldReferenceIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_form", "form", "pe_order", List.of(Map.of(
                "id", "basic_section",
                "blockType", "form-section",
                "fields", List.of(Map.of(
                        "label", localized("Order No"),
                        "component", "input"
                ))
        )))));

        assertHasError(validate(manifest), "S-PAGE-FIELD-REF", "pages[0].blocks[0].fields[0].field");
    }

    @Test
    void toolbarWithoutButtonsIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "list_toolbar",
                "blockType", "toolbar"
        )))));

        assertHasError(validate(manifest), "S-PAGE-BUTTONS", "pages[0].blocks[0].buttons");
    }

    @Test
    void buttonUsingRawCodeAsLabelIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "list_toolbar",
                "blockType", "toolbar",
                "buttons", List.of(Map.of(
                        "action", "create",
                        "label", "create"
                ))
        )))));

        assertHasError(validate(manifest), "S-PAGE-LABEL", "pages[0].blocks[0].buttons[0].label");
    }

    @Test
    void buttonContentCanSatisfyBusinessLabel() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "list_toolbar",
                "blockType", "toolbar",
                "buttons", List.of(Map.of(
                        "action", "create",
                        "content", localized("New Order")
                ))
        )))));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-LABEL".equals(m.getCode())),
                () -> "Expected button content to satisfy business label but got " + messages);
    }

    // ===== Phase B: v4 structural import-format checks (hard-fail candidates) =====

    @Test
    void pageWithoutSchemaVersionIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_list", "list", "pe_order", validTable());
        p.setSchemaVersion(null);
        manifest.setPages(List.of(p));
        assertHasError(validate(manifest), "S-PAGE-VERSION", "pages[0].schemaVersion");
    }

    @Test
    void pageWithLegacySchemaVersionIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_list", "list", "pe_order", validTable());
        p.setSchemaVersion(2);
        manifest.setPages(List.of(p));
        assertHasError(validate(manifest), "S-PAGE-VERSION", "pages[0].schemaVersion");
    }

    @Test
    void pageWithSchemaVersion4HasNoVersionError() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_order_list", "list", "pe_order", validTable())));
        assertNoError(validate(manifest), "S-PAGE-VERSION");
    }

    @Test
    void dashboardKindIsRejectedForImport() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_dash", "dashboard", "pe_order", validTable())));
        assertHasError(validate(manifest), "S-PAGE-KIND-UNKNOWN", "pages[0].kind");
    }

    @Test
    void compositeKindIsRejectedForImport() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        manifest.setPages(List.of(page("pe_comp", "composite", "pe_order", validTable())));
        assertHasError(validate(manifest), "S-PAGE-KIND-UNKNOWN", "pages[0].kind");
    }

    @Test
    void layoutTypeOutsideGridStackIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_list", "list", "pe_order", validTable());
        p.setLayout(Map.of("type", "flex"));
        manifest.setPages(List.of(p));
        assertHasError(validate(manifest), "S-PAGE-LAYOUT-TYPE", "pages[0].layout.type");
    }

    @Test
    void layoutTypeGridIsAccepted() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_list", "list", "pe_order", validTable());
        p.setLayout(Map.of("type", "grid", "cols", 12));
        manifest.setPages(List.of(p));
        assertNoError(validate(manifest), "S-PAGE-LAYOUT-TYPE");
    }

    @Test
    void blockColExceedingGridWidthIsRejected() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "layout", Map.of("col", 10, "colSpan", 6),
                "columns", List.of(column("pe_order_no", localized("Order No"))))));
        p.setLayout(Map.of("type", "grid", "cols", 12));
        manifest.setPages(List.of(p));
        assertHasError(validate(manifest), "S-PAGE-BLOCK-COL", "pages[0].blocks[0].layout.col");
    }

    @Test
    void blockColWithinGridWidthIsAccepted() {
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_list", "list", "pe_order", List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "layout", Map.of("col", 0, "colSpan", 8),
                "columns", List.of(column("pe_order_no", localized("Order No"))))));
        p.setLayout(Map.of("type", "grid", "cols", 12));
        manifest.setPages(List.of(p));
        assertNoError(validate(manifest), "S-PAGE-BLOCK-COL");
    }

    @Test
    void recordSourceTopLevelFieldDoesNotProduceUnknownFieldsError() {
        // Regression guard: a form page that declares a top-level "recordSource" object
        // must NOT be rejected with S-PAGE-UNKNOWN-FIELDS.  Before this fix the field
        // was not declared on PageSchemaDTO (imports) so Jackson routed it to
        // @JsonAnySetter → unknownFields → validator flagged it as unknown.
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("qr_code_form", "form", "pe_order", List.of(Map.of(
                "id", "basic_section",
                "blockType", "form-section",
                "fields", List.of(Map.of(
                        "field", "pe_order_no",
                        "component", "input",
                        "required", true
                ))
        )));
        p.setRecordSource(Map.of("endpoint", "/api/qr/{recordPid}"));
        manifest.setPages(List.of(p));

        List<PluginValidationMessage> messages = validate(manifest);
        assertTrue(messages.stream().noneMatch(m -> "S-PAGE-UNKNOWN-FIELDS".equals(m.getCode())),
                () -> "Expected recordSource to be a first-class page field (no S-PAGE-UNKNOWN-FIELDS) but got " + messages);
    }

    @Test
    void recordSourceDeserializesAsFirstClassPageProperty() throws Exception {
        // Verify the imports DTO deserialization path: a JSON page with "recordSource"
        // must bind to PageSchemaDTO.recordSource (non-null getRecordSource()) and must
        // NOT appear in getUnknownFields().
        PageSchemaDTO page = new ObjectMapper().readValue("""
                {
                  "pageKey": "qr_code_form",
                  "kind": "form",
                  "schemaVersion": 4,
                  "modelCode": "ab_qr_code",
                  "layout": { "type": "stack" },
                  "recordSource": { "endpoint": "/api/qr/{recordPid}" },
                  "blocks": [
                    {
                      "id": "basic_section",
                      "blockType": "form-section",
                      "fields": [
                        { "field": "name", "component": "input", "required": true }
                      ]
                    }
                  ]
                }
                """, PageSchemaDTO.class);

        assertTrue(page.getRecordSource() != null,
                "Expected getRecordSource() to be non-null after deserialization");
        assertTrue(page.getRecordSource().containsKey("endpoint"),
                () -> "Expected recordSource to contain 'endpoint' key but got: " + page.getRecordSource());
        assertTrue(page.getUnknownFields() == null || !page.getUnknownFields().containsKey("recordSource"),
                () -> "Expected recordSource NOT to be treated as an unknown field: " + page.getUnknownFields());
    }

    @Test
    void traceGraphBlockIsAccepted() {
        // Regression: TraceGraphBlockRenderer (#450) registered the "trace-graph" block
        // in the frontend BlockRegistry but left DslRegistry.BlockType untouched, so
        // PageSchemaValidator rejected every page mounting it with S-PAGE-BLOCK-TYPE.
        // Mirrors the pe_production_plan_detail consumption-trace mount.
        PluginManifestExtended manifest = manifestWithOrderModel();
        PageSchemaDTO p = page("pe_order_detail", "detail", "pe_order", List.of(Map.of(
                "id", "consumption_trace",
                "blockType", "trace-graph",
                "dataSource", "consumptionTrace",
                "mode", "consumption")));
        p.setDataSources(Map.of(
                "consumptionTrace", Map.of(
                        "type", "api",
                        "endpoint", "/api/datasource/list",
                        "method", "get",
                        "params", Map.of(
                                "datasourceId", "nq:pe_consumption_trace_by_lot",
                                "format", "records",
                                "workOrderPid", "${$page.recordPid}"))));
        manifest.setPages(List.of(p));
        assertNoError(validate(manifest), "S-PAGE-BLOCK-TYPE");
    }

    private List<PluginValidationMessage> validate(PluginManifestExtended manifest) {
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();
        return validator.validate(ctx);
    }

    private PluginManifestExtended manifestWithOrderModel() {
        PluginManifestExtended manifest = new PluginManifestExtended();

        FieldDefinitionDTO orderNo = new FieldDefinitionDTO();
        orderNo.setCode("pe_order_no");
        orderNo.setDataType("string");
        orderNo.setDisplayNameEn("Order No");
        FieldDefinitionDTO.FieldConstraints constraints = new FieldDefinitionDTO.FieldConstraints();
        constraints.setRequired(true);
        orderNo.setConstraints(constraints);

        FieldDefinitionDTO title = new FieldDefinitionDTO();
        title.setCode("title");
        title.setDataType("string");
        title.setDisplayNameEn("Title");

        FieldDefinitionDTO unlabeled = new FieldDefinitionDTO();
        unlabeled.setCode("pe_unlabeled");
        unlabeled.setDataType("string");

        FieldDefinitionDTO status = new FieldDefinitionDTO();
        status.setCode("pe_status");
        status.setDataType("enum");
        status.setDisplayNameEn("Status");
        status.setDictCode("pe_order_status");

        ModelFieldBindingDTO orderNoBinding = new ModelFieldBindingDTO();
        orderNoBinding.setModelCode("pe_order");
        orderNoBinding.setFieldCode("pe_order_no");
        orderNoBinding.setRequired(true);

        ModelFieldBindingDTO titleBinding = new ModelFieldBindingDTO();
        titleBinding.setModelCode("pe_order");
        titleBinding.setFieldCode("title");

        ModelFieldBindingDTO unlabeledBinding = new ModelFieldBindingDTO();
        unlabeledBinding.setModelCode("pe_order");
        unlabeledBinding.setFieldCode("pe_unlabeled");

        ModelFieldBindingDTO statusBinding = new ModelFieldBindingDTO();
        statusBinding.setModelCode("pe_order");
        statusBinding.setFieldCode("pe_status");

        manifest.setFields(List.of(orderNo, title, unlabeled, status));
        manifest.setDicts(new ArrayList<>(List.of(dict("pe_order_status"))));
        manifest.setModelFieldBindings(List.of(orderNoBinding, titleBinding, unlabeledBinding, statusBinding));
        return manifest;
    }

    private DictDefinitionDTO dict(String code) {
        DictDefinitionDTO dict = new DictDefinitionDTO();
        dict.setCode(code);
        dict.setName(code);
        dict.setDictType("static");
        return dict;
    }

    private PageSchemaDTO page(String pageKey, String kind, String modelCode, List<Object> blocks) {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey(pageKey);
        page.setKind(kind);
        page.setModelCode(modelCode);
        page.setSchemaVersion(4);
        page.setLayout(Map.of("type", "stack"));
        page.setBlocks(blocks);
        return page;
    }

    private List<Object> validTable() {
        return List.of(Map.of(
                "id", "orders_table",
                "blockType", "table",
                "columns", List.of(column("pe_order_no", localized("Order No")))));
    }

    private void assertNoError(List<PluginValidationMessage> messages, String code) {
        assertTrue(messages.stream().noneMatch(m -> code.equals(m.getCode())),
                () -> "Expected no " + code + " but got " + messages);
    }

    private Map<String, Object> column(String field, Object label) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("field", field);
        column.put("label", label);
        return column;
    }

    private Map<String, Object> localized(String en) {
        return Map.of("zh-CN", en, "en", en);
    }

    private void assertHasError(List<PluginValidationMessage> messages, String code, String path) {
        assertTrue(messages.stream().anyMatch(m -> code.equals(m.getCode())
                        && path.equals(m.getPath())
                        && m.isError()),
                () -> "Expected error " + code + " at " + path + " but got " + messages);
    }
}
