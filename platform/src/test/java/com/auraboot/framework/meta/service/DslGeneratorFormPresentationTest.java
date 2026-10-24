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
}
