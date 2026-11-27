package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.validation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginPackageReplacementDeclarationTest {
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PluginResourceMapper tracking = mock(PluginResourceMapper.class);

    PluginPackageReplacements replacements() throws Exception {
        var result = new PluginPackageReplacements(jdbc,json,tracking);
        ReflectionTestUtils.setField(result,"contracts",List.of(json.readTree("""
                {"oldPluginId":"com.test.old","receiverPluginId":"com.test.receiver",
                 "declarations":{"menus":[{"code":"received","name":"Received","type":1,"visible":true}]}}
                """)));
        return result;
    }

    PageSchemaDTO page() {
        return PageSchemaDTO.builder().pageKey("pe_order_list").name("Orders").kind("list").schemaVersion(4)
                .layout(Map.of("type","stack")).blocks(List.of(Map.of("id","orders","blockType","table",
                        "columns",List.of(Map.of("field","order_no","label",Map.of("en","No","zh-CN","单号")))))).build();
    }

    @ParameterizedTest
    @ValueSource(strings={"com.test.ordinary","com.test.receiver"})
    void contributionsRetainCleanPageDtosWithoutComputedFields(String id) throws Exception {
        var page=page(); var manifest=PluginManifestExtended.builder().pluginId(id).pages(List.of(page)).build();
        var result=replacements().contribute(manifest);
        assertThat(result).isNotSameAs(manifest);
        assertThat(result.getPages().getFirst()).isSameAs(page);
        assertThat(page.getUnknownFields()).isNullOrEmpty();
        assertThatCode(() -> new PageSchemaImportGate(new PageSchemaValidator(),json).enforce(result)).doesNotThrowAnyException();
        assertThat(manifest.getMenus()).isNull();
        if(id.endsWith("receiver")) assertThat(result.getMenus()).extracting(MenuDefinitionDTO::getCode).containsExactly("received");
        verifyNoInteractions(jdbc,tracking);
    }

    @Test
    void genuineUnknownPageFieldsStillRejectRatherThanBeingStripped() throws Exception {
        var page=page();page.setUnknownField("obsolete_page_setting",true);
        var result=replacements().contribute(PluginManifestExtended.builder().pluginId("com.test.receiver").pages(List.of(page)).build());
        assertThat(result.getPages().getFirst().getUnknownFields()).containsEntry("obsolete_page_setting",true);
        assertThat(new PageSchemaValidator().validate(PluginValidationContext.builder().manifest(result).build()))
                .extracting(PluginValidationMessage::getCode).contains("S-PAGE-UNKNOWN-FIELDS");
    }

    @Test
    void conflictingReplacementCannotSilentlyOverrideAnExistingDeclaration() throws Exception {
        var manifest=PluginManifestExtended.builder().pluginId("com.test.receiver").menus(List.of(
                MenuDefinitionDTO.builder().code("received").name("Different owner configuration").build())).build();
        assertThatThrownBy(() -> replacements().contribute(manifest)).hasMessageContaining("Conflicting");
        assertThat(manifest.getMenus().getFirst().getName()).isEqualTo("Different owner configuration");
    }
}
