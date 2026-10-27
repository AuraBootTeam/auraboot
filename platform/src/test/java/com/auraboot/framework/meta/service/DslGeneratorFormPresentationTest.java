package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.generator.DslGeneratorImpl;
import com.auraboot.framework.meta.template.generator.FieldTypeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DslGeneratorFormPresentationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FieldTypeMapper mapper = new FieldTypeMapper();
    private final DslGeneratorImpl generator = new DslGeneratorImpl(json, mapper, mock(MetaFieldDictBindingMapper.class));
    private final Model model = new Model();
    @BeforeEach void setup() {
        MetaContext.setContext(7L, 99L, "actor", "tester");
        model.setCode("delivery");
        var extension = new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(java.util.Map.of("displayName", "Delivery")); model.setExtension(extension);
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private Field field(long id, String code, String type) {
        var field = new Field(); field.setId(id); field.setCode(code); field.setDataType(type); return field;
    }
    private ModelFieldBinding binding(long id) {
        var binding = new ModelFieldBinding(); binding.setFieldId(id); binding.setEditable(true); return binding;
    }
    @Test void emitsCurrentSchemaAndFullWidthLongTextWithSmartControls() throws Exception {
        var page = generator.generateFormPage(model, List.of(binding(1), binding(2)), List.of(field(1,"name","string"), field(2,"notes","text")), new CrudTemplateConfig());
        assertThat(page.getSchemaVersion()).isEqualTo(4);
        var fields = json.readTree(page.getBlocks()).get(0).path("fields");
        assertThat(fields.get(0).path("layout").path("colSpan").asInt()).isEqualTo(6);
        assertThat(fields.get(1).path("layout").path("colSpan").asInt()).isEqualTo(12);
        assertThat(fields.get(1).path("component").asText()).isEqualTo("SmartTextarea");
    }
    @Test void mapsTypedFieldsCaseInsensitivelyToSmartControls() {
        assertThat(mapper.getFormComponent(field(1, "amount", "MONEY"))).isEqualTo("SmartMoneyInput");
        assertThat(mapper.getFormComponent(field(1, "due", "DATE"))).isEqualTo("SmartDatePicker");
        assertThat(mapper.getFormComponent(field(1, "active", "Boolean"))).isEqualTo("SmartSwitch");
        assertThat(mapper.getFormComponent(field(1, "owner", "reference"))).isEqualTo("SmartSelect");
        assertThat(mapper.getFormComponent(field(1, "at", "datetime"))).isEqualTo("SmartDatePicker");
        assertThat(mapper.isNumberField(field(1, "amount", "DECIMAL"))).isTrue();
        assertThat(mapper.getColumnValueType(field(1, "amount", "money"))).isEqualTo("currency");
    }
    @Test void persistsConfiguredLayoutAndRejectsInvalidColumns() {
        var config = new CrudTemplateConfig(); var presentation = new CrudTemplateConfig.FormPresentation();
        presentation.setColumns(3); presentation.setWidth(CrudTemplateConfig.FormPresentation.Width.FULL);
        config.setFormPresentation(presentation);
        var page = generator.generateFormPage(model, List.of(), List.of(), config);
        assertThat(page.getExtension().getExtension().get("formPresentation")).isInstanceOf(java.util.Map.class);
        var value = (java.util.Map<?, ?>) page.getExtension().getExtension().get("formPresentation");
        assertThat(value.get("columns")).isEqualTo(3); assertThat(value.get("width")).isEqualTo("full");
        presentation.setColumns(4);
        assertThatThrownBy(() -> generator.generateFormPage(model,List.of(),List.of(),config)).hasMessageContaining("Form columns");
    }
    @Test void groupsOnlyExplicitFieldsAndPreservesUnassignedFields() throws Exception {
        var config = new CrudTemplateConfig(); var group = new CrudTemplateConfig.FormGroup();
        group.setTitle("Planning"); group.setFields(List.of("notes")); group.setCollapsible(true); config.setFormGroups(List.of(group));
        var page = generator.generateFormPage(model, List.of(binding(1),binding(2)), List.of(field(1,"name","string"),field(2,"notes","text")), config);
        var blocks = json.readTree(page.getBlocks());
        assertThat(blocks.get(0).path("title").asText()).isEqualTo("Planning");
        assertThat(blocks.get(0).path("extension").path("collapsible").asBoolean()).isTrue();
        assertThat(blocks.get(1).path("fields").get(0).path("field").asText()).isEqualTo("name");
        assertThat(blocks.get(2).path("blockType").asText()).isEqualTo("form-buttons");
    }
    @Test void rejectsUnknownAndRepeatedGroupFields() {
        var config = new CrudTemplateConfig(); var group = new CrudTemplateConfig.FormGroup();
        group.setFields(List.of("missing")); config.setFormGroups(List.of(group));
        assertThatThrownBy(() -> generator.generateFormPage(model,List.of(),List.of(),config)).hasMessageContaining("Unknown form group field");
        group.setFields(List.of("name","name"));
        assertThatThrownBy(() -> generator.generateFormPage(model,List.of(binding(1)),List.of(field(1,"name","string")),config)).hasMessageContaining("Duplicate form group field");
    }
    @Test void usesTypedSmartControlsForZeroConfigGeneration() {
        assertThat(mapper.getFormComponent(field(1, "quantity", "INTEGER"))).isEqualTo("SmartNumberInput");
        assertThat(mapper.getFormComponent(field(2, "payload", "jsonb"))).isEqualTo("SmartJsonEditor");
        assertThat(mapper.getFormComponent(field(3, "attachment", "file"))).isEqualTo("SmartUpload");
    }
    @Test void zeroConfigDetailsUseDeclaredIdentityAndFullWidthContent() throws Exception {
        model.getExtension().setExtension(java.util.Map.of("titleField", "name"));
        var one = binding(1); one.setVisible(true);
        var two = binding(2); two.setVisible(true);
        var page = generator.generateDetailPage(model, List.of(one, two),
                List.of(field(1, "name", "string"), field(2, "notes", "text")), new CrudTemplateConfig());
        assertThat(page.getExtension().get("headerTitleField")).isEqualTo("name");
        assertThat(page.getExtension().get("presentation")).isNull();
        var detailFields = json.readTree(page.getBlocks()).get(0).path("fields");
        assertThat(detailFields.get(1).path("layout").path("colSpan").asInt()).isEqualTo(12);
        assertThat(detailFields.get(1).path("component").isMissingNode()).isTrue();
        assertThat(detailFields.get(1).path("dataType").asText()).isEqualTo("text");
    }
    @Test void generatedReadOnlyFieldsAreNotRequiredInputs() throws Exception {
        var locked = binding(1); locked.setVisible(true); locked.setEditable(false); locked.setRequired(true);
        var page = generator.generateFormPage(model, List.of(locked),
                List.of(field(1, "name", "string")), new CrudTemplateConfig());
        var value = json.readTree(page.getBlocks()).get(0).path("fields").get(0);
        assertThat(value.path("readOnly").asBoolean()).isTrue();
        assertThat(value.path("validation").isMissingNode()).isTrue();
    }
    @Test void defaultFormOmitsInfrastructureButDetailRetainsVisibleAuditFields() throws Exception {
        var created = binding(1); created.setVisible(true);
        var updated = binding(2); updated.setVisible(true);
        var due = binding(3); due.setVisible(true);
        var bindings = List.of(created, updated, due);
        var fields = List.of(field(1, "created_at", "datetime"),
                field(2, "updated_at", "datetime"), field(3, "due_date", "datetime"));
        var form = json.readTree(generator.generateFormPage(model, bindings, fields, new CrudTemplateConfig()).getBlocks());
        var inputs = form.get(0).path("fields");
        assertThat(inputs.size()).isEqualTo(1);
        assertThat(inputs.get(0).path("field").asText()).isEqualTo("due_date");
        assertThat(inputs.get(0).path("readOnly").asBoolean()).isFalse();
        var detail = json.readTree(generator.generateDetailPage(model, bindings, fields, new CrudTemplateConfig()).getBlocks());
        assertThat(detail.get(0).path("fields").findValuesAsText("field"))
                .contains("created_at", "updated_at", "due_date");
    }
    @Test void explicitlySelectedInfrastructureRemainsReadOnlyEvenWithEditableBinding() throws Exception {
        var config = new CrudTemplateConfig(); config.setFormFields(List.of("created_at"));
        var created = binding(1); created.setVisible(true); created.setRequired(true);
        var page = generator.generateFormPage(model, List.of(created),
                List.of(field(1, "created_at", "datetime")), config);
        var input = json.readTree(page.getBlocks()).get(0).path("fields").get(0);
        assertThat(input.path("readOnly").asBoolean()).isTrue();
        assertThat(input.path("validation").isMissingNode()).isTrue();
    }
    @Test void declaredIdentityMustBeVisibleAndBound() {
        model.getExtension().setExtension(java.util.Map.of("titleField", "secret"));
        var one = binding(1); one.setVisible(false);
        var page = generator.generateDetailPage(model, List.of(one),
                List.of(field(1, "secret", "string")), new CrudTemplateConfig());
        assertThat(page.getExtension() == null ? null : page.getExtension().get("headerTitleField")).isNull();
    }

    @Test void respectsRegisteredModelComponentOverrides() {
        var notes = field(1, "notes", "string");
        var extension = new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(java.util.Map.of("renderComponent", "SmartRichTextEditor")); notes.setExtension(extension);
        assertThat(mapper.getFormComponent(notes)).isEqualTo("SmartRichTextEditor");
    }
    @Test void limitsDefaultColumnsButPreservesExplicitSelectionsAndBindingOrder() throws Exception {
        var fields = new java.util.ArrayList<Field>();
        var bindings = new java.util.ArrayList<ModelFieldBinding>();
        for (int i = 1; i <= 9; i++) {
            fields.add(field(i, "f" + i, "string"));
            var binding = binding(i); binding.setVisible(true); binding.setFieldOrder(10 - i); bindings.add(binding);
        }
        var page = generator.generateListPage(model, bindings, fields, new CrudTemplateConfig());
        var columns = json.readTree(page.getBlocks()).findValue("columns");
        assertThat(columns.size()).isEqualTo(8);
        assertThat(columns.get(0).path("field").asText()).isEqualTo("f9");
        var config = new CrudTemplateConfig(); config.setListColumns(fields.stream().map(Field::getCode).toList());
        columns = json.readTree(generator.generateListPage(model, bindings, fields, config).getBlocks()).findValue("columns");
        assertThat(columns.size()).isEqualTo(10);
        assertThat(columns.get(0).path("field").asText()).isEqualTo("f1");
    }

    @Test void generatesOneDetailToolbarAndModeAwareFormTitlesWithoutVisualOverrides() throws Exception {
        var config = new CrudTemplateConfig();
        var form = generator.generateFormPage(model, List.of(), List.of(), config);
        assertThat(form.getExtension().get("formPresentation")).isNull();
        assertThat(form.getExtension().get("createTitle")).isEqualTo(java.util.Map.of("zh-CN", "新建Delivery", "en", "New Delivery"));
        assertThat(form.getExtension().get("editTitle")).isEqualTo(java.util.Map.of("zh-CN", "编辑Delivery", "en", "Edit Delivery"));
        var detail = json.readTree(generator.generateDetailPage(model, List.of(), List.of(), config).getBlocks());
        assertThat(detail.get(1).path("blockType").asText()).isEqualTo("toolbar");
        assertThat(detail.get(1).path("buttons").size()).isEqualTo(1);
        assertThat(detail.get(1).path("buttons").get(0).path("code").asText()).isEqualTo("edit");
        assertThat(detail.get(1).path("buttons").get(0).path("permissionCode").asText()).isEqualTo("model.delivery.update");
    }

    @Test void preservesFieldGuidanceWithoutViolatingPageI18nContract() throws Exception {
        var field = field(1, "stage", "string");
        var ui = new com.auraboot.framework.meta.entity.payload.FieldUiSchemaBean();
        ui.setHelpText("按实际交付阶段维护项目状态。");
        ui.setPlaceholder("$i18n:delivery.stage.placeholder"); field.setUiSchema(ui);
        var page = generator.generateFormPage(model, List.of(binding(1)), List.of(field), new CrudTemplateConfig());
        var blocks = json.readTree(page.getBlocks());
        assertThat(blocks.get(0).path("fields").get(0).path("helpText").path("zh-CN").asText()).isEqualTo(ui.getHelpText());
        assertThat(blocks.get(0).path("fields").get(0).path("placeholder").asText()).isEqualTo(ui.getPlaceholder());
        var schema = java.util.Map.<String, Object>of("blocks", json.readValue(page.getBlocks(), List.class));
        assertThatCode(() -> com.auraboot.framework.meta.validator.PageSchemaDslI18nValidator.validatePageSchema(schema, "delivery_form")).doesNotThrowAnyException();
    }
}
