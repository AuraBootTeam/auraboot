package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.generator.DslGeneratorImpl;
import com.auraboot.framework.meta.template.generator.FieldTypeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Generated DSL must enforce the same model permission namespace as DynamicController. */
class DslGeneratorPermissionContractTest {
    @org.junit.jupiter.api.BeforeEach void context() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L, 99L, "actor", "tester");
    }
    @org.junit.jupiter.api.AfterEach void clear() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }
    @ParameterizedTest @ValueSource(strings = {"list", "form", "detail"})
    void keepsCanonicalPermissionGuardsInEveryGeneratedSurface(String kind) throws Exception {
        ObjectMapper json = new ObjectMapper();
        var generator = new DslGeneratorImpl(json, mock(FieldTypeMapper.class), mock(MetaFieldDictBindingMapper.class));
        var model = new Model(); model.setCode("customer");
        var extension = new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(java.util.Map.of("displayName", "客户")); model.setExtension(extension);
        var config = new CrudTemplateConfig(); config.setEnableExport(true); config.setEnableImport(true);
        PageSchema page = switch (kind) {
            case "list" -> generator.generateListPage(model, List.of(), List.of(), config);
            case "form" -> generator.generateFormPage(model, List.of(), List.of(), config);
            default -> generator.generateDetailPage(model, List.of(), List.of(), config);
        };
        var blocks = json.readTree(page.getBlocks());
        var permissionCodes = blocks.findValues("permissionCode");
        var expressions = blocks.findValues("visibleWhen");
        if ("form".equals(kind)) {
            // A shared create/edit form has a real authenticated expression context.
            assertThat(expressions).anySatisfy(expression -> assertThat(expression.asText())
                    .isEqualTo("${hasPermission('model.customer.create') || hasPermission('model.customer.update')}"));
        } else {
            assertThat(permissionCodes).isNotEmpty().allSatisfy(permission ->
                    assertThat(permission.asText()).startsWith("model.customer."));
            assertThat(expressions).noneSatisfy(expression ->
                    assertThat(expression.asText()).contains("hasPermission("));
        }
        String all = permissionCodes.toString() + expressions;
        if ("list".equals(kind)) {
            assertThat(all).contains("model.customer.read", "model.customer.create", "model.customer.update", "model.customer.delete");
            var expected = java.util.Map.of("create", "new", "edit", "edit", "view", "view");
            for (var entry : expected.entrySet()) {
                var buttons = blocks.findParents("code").stream()
                        .filter(button -> entry.getKey().equals(button.path("code").asText())).toList();
                assertThat(buttons).hasSize(1);
                assertThat(buttons.getFirst().path("action").path("type").asText()).isEqualTo("builtin");
                assertThat(buttons.getFirst().path("action").path("name").asText()).isEqualTo(entry.getValue());
                assertThat(buttons.getFirst().has("events")).isFalse();
            }
        } else if ("form".equals(kind)) {
            assertThat(all).contains("model.customer.create", "model.customer.update");
        } else assertThat(all).contains("model.customer.update");
    }
}
