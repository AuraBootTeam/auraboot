package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class PageSchemaValidatorBoundaryTest {
    private final PageSchemaValidator validator = new PageSchemaValidator();

    @Test
    void gridWidthComparisonCannotOverflowAndAcceptAnOutOfBoundsSpan() {
        var block = table(Map.of("field", "name", "label", "Name"));
        block.put("layout", Map.of("col", 1, "colSpan", Integer.MAX_VALUE));
        assertTrue(codes(validate(page(block))).contains("S-PAGE-BLOCK-COL"));
    }

    @Test
    void nullDictionaryEntriesDoNotSuppressPageFindings() {
        var manifest = manifest(page(table(Map.of("field", "name"))));
        var dict = new DictDefinitionDTO(); dict.setCode("test_dict");
        manifest.setDicts(Arrays.asList(null, new DictDefinitionDTO(), dict));
        assertTrue(codes(validate(manifest)).contains("S-PAGE-LABEL"));
    }

    @ParameterizedTest
    @MethodSource("dataSources")
    void externalDataSourcesHaveDifferentFieldBindingRequirements(Object dataSource, boolean external) {
        var block = table(Map.of("field", "unbound", "label", "Name")); block.put("dataSource", dataSource);
        var manifest = manifest(page(block)); manifest.setModelFieldBindings(List.of(binding("name", false)));
        var messages = validate(manifest);
        assertEquals(!external, codes(messages).contains("S-PAGE-FIELD-REF"));
    }
    static Stream<Arguments> dataSources() {
        return Stream.of(Arguments.of(" ", false), Arguments.of(1, false), Arguments.of(Map.of("type", "entity"), false),
            Arguments.of(Map.of("type", "namedQuery"), true), Arguments.of(Map.of("type", "api"), true),
            Arguments.of(Map.of("params", Map.of("datasourceId", "nq:query")), true),
            Arguments.of(Map.of("params", Map.of("datasourceId", "model:entity")), false),
            Arguments.of(Map.of("params", Map.of()), false), Arguments.of(Map.of("params", "wrong"), false));
    }

    @ParameterizedTest
    @CsvSource({"false,false,false,true", "true,false,false,false", "false,true,false,false", "false,false,true,false"})
    void editableRequiredFieldsNeedARequiredProjection(boolean readOnly, boolean hidden, boolean required, boolean finding) {
        var field = new HashMap<String, Object>(); field.put("field", "name"); field.put("readOnly", readOnly); field.put("hidden", hidden); field.put("required", required);
        var page = page(Map.of("id", "form", "blockType", "form-section", "fields", List.of(field))); page.setKind("form");
        var manifest = manifest(page); manifest.setModelFieldBindings(List.of(binding("name", true)));
        assertEquals(finding, codes(validate(manifest)).contains("S-PAGE-FORM-REQUIRED"));
    }

    @ParameterizedTest
    @MethodSource("labels")
    void requiredButtonLabelsNeedSomeNonblankTranslation(Object label, boolean missing) {
        var button = new HashMap<String, Object>(); button.put("label", label);
        var messages = validate(page(Map.of("id", "toolbar", "blockType", "toolbar", "buttons", List.of(button))));
        assertEquals(missing, codes(messages).contains("S-PAGE-LABEL"));
    }
    static Stream<Arguments> labels() {
        return Stream.of(Arguments.of(null, true), Arguments.of(" ", true), Arguments.of(Map.of(), true),
            Arguments.of(Map.of("en-US", " "), true), Arguments.of(Map.of("en-US", 1), true),
            Arguments.of(Map.of("en-US", "Name"), false), Arguments.of("$i18n:button.name", false));
    }

    @Test
    void nestedSubTableKeepsItsOwnColumnsAndButtonsPaths() {
        var subTable = Map.of("childModel", "test_entity", "columns", List.of(Map.of("field", "name", "label", "Name")),
            "actions", List.of(Map.of("content", "name", "code", "name")));
        var messages = validate(page(Map.of("id", "sub", "blockType", "sub-table", "subTable", subTable)));
        var finding = messages.stream().filter(m -> "S-PAGE-LABEL".equals(m.getCode())).findFirst().orElseThrow();
        assertEquals("pages[0].blocks[0].subTable.actions[0].content", finding.getPath());
    }

    @Test
    void incompleteModelMetadataAndNonObjectColumnsAreHandledWithoutLosingFindings() {
        var field = new FieldDefinitionDTO(); field.setCode("name"); field.setDisplayName("name"); field.setDisplayNameZhCN("Name");
        field.setExtension(Map.of("dictCode", "test_dict"));
        var manifest = manifest(page(table(Map.of("field", "name", "dictCode", "test_dict"))));
        manifest.setFields(Arrays.asList(null, new FieldDefinitionDTO(), field));
        manifest.setModelFieldBindings(Arrays.asList(null, new ModelFieldBindingDTO(), binding(null, false), binding("name", false)));
        assertFalse(codes(validate(manifest)).contains("S-PAGE-LABEL"));
        var block = table(Map.of("field", " ", "label", "Name")); block.put("columns", Arrays.asList(null, 1, Map.of("label", "Name")));
        assertTrue(codes(validate(page(block))).contains("S-PAGE-FIELD-REF"));
    }

    @Test
    void hardcodedBlockAndNestedTextsProduceTraceableI18nFindings() {
        var page = page(Map.of("id", "tabs", "blockType", "tabs", "title", "标题", "tabs", Arrays.asList(null, 1, Map.of("label", "标签"))));
        page.setTitle(Map.of("zh-CN", "页面", "en-US", "Page"));
        var paths = validate(page).stream().filter(m -> "S-PAGE-I18N".equals(m.getCode())).map(PluginValidationMessage::getPath).toList();
        assertFalse(paths.contains("pages[0].title")); assertTrue(paths.contains("pages[0].blocks[0].title"));
        assertTrue(paths.contains("pages[0].blocks[0].tabs[2].label"));
    }

    @Test
    void missingPageEnvelopePropertiesProduceSpecificFindings() {
        var page = new PageSchemaDTO(); page.setKind(" "); page.setLayout(Map.of()); page.setBlocks(List.of());
        var messages = codes(validate(page));
        assertTrue(messages.containsAll(List.of("S-PAGE-KIND", "S-PAGE-LAYOUT", "S-PAGE-BLOCKS")));
        page.setLayout(Map.of("cols", 12)); page.setKind(null);
        assertTrue(codes(validate(page)).contains("S-PAGE-LAYOUT-TYPE"));
    }

    @Test
    void flatSubTableWithoutSpanUsesItsRootColumnsAndDefaultZeroSpan() {
        var block = table(Map.of("field", "name", "label", "Name")); block.put("blockType", "sub-table");
        block.put("layout", Map.of("col", 0));
        var messages = codes(validate(page(block)));
        assertFalse(messages.contains("S-PAGE-TABLE-COLUMNS"));
        assertFalse(messages.contains("S-PAGE-BLOCK-COL"));
    }

    @Test
    void globalRequiredConstraintsApplyOnlyToEditableForms() {
        var field = new FieldDefinitionDTO(); field.setCode("name");
        var constraints = new FieldDefinitionDTO.FieldConstraints(); constraints.setRequired(true); field.setConstraints(constraints);
        var page = page(Map.of("id", "form", "blockType", "form-section", "fields", List.of(Map.of("field", "name"))));
        page.setKind("form"); var manifest = manifest(page); manifest.setFields(List.of(field));
        var noModel = binding("ignored", false); noModel.setModelCode(" ");
        manifest.setModelFieldBindings(List.of(noModel, binding("name", false)));
        assertTrue(codes(validate(manifest)).contains("S-PAGE-FORM-REQUIRED"));
        page.setKind("detail");
        assertFalse(codes(validate(manifest)).contains("S-PAGE-FORM-REQUIRED"));
        constraints.setRequired(false); page.setKind("form");
        assertFalse(codes(validate(manifest)).contains("S-PAGE-FORM-REQUIRED"));
    }

    private List<PluginValidationMessage> validate(PageSchemaDTO page) { return validate(manifest(page)); }
    private List<PluginValidationMessage> validate(PluginManifestExtended manifest) { return validator.validate(PluginValidationContext.builder().manifest(manifest).build()); }
    private List<String> codes(List<PluginValidationMessage> messages) { return messages.stream().map(PluginValidationMessage::getCode).toList(); }
    private PluginManifestExtended manifest(PageSchemaDTO page) { var manifest = new PluginManifestExtended(); manifest.setPages(List.of(page)); return manifest; }
    private PageSchemaDTO page(Map<String, Object> block) {
        var page = new PageSchemaDTO(); page.setPageKey("test_page"); page.setModelCode("test_entity"); page.setKind("list"); page.setSchemaVersion(4);
        page.setLayout(Map.of("type", "grid", "cols", 12)); page.setBlocks(List.of(block)); return page;
    }
    private Map<String, Object> table(Map<String, Object> column) { return new HashMap<>(Map.of("id", "table", "blockType", "table", "columns", List.of(column))); }
    private ModelFieldBindingDTO binding(String field, boolean required) {
        var binding = new ModelFieldBindingDTO(); binding.setModelCode("test_entity"); binding.setFieldCode(field); binding.setRequired(required); return binding;
    }
}
