package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporterImpl;
import com.auraboot.framework.meta.converter.PageSchemaConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class PageSchemaLinkageContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Object> rules = List.of(Map.of(
            "id", "clear-zone",
            "trigger", Map.of("fieldCode", "warehouse", "event", "change"),
            "actions", List.of(Map.of("type", "setValue", "target", "zone", "value", "null"))
    ));

    private PageSchemaDTO importedPage() throws Exception {
        return mapper.readValue(mapper.writeValueAsString(Map.of(
                "pageKey", "location_form",
                "kind", "form",
                "schemaVersion", 4,
                "layout", Map.of("type", "stack"),
                "blocks", List.of(),
                "linkageRules", rules
        )), PageSchemaDTO.class);
    }

    @Test
    void recognizesExistingRuntimeLinkageRulesAtTheImportBoundary() throws Exception {
        PageSchemaDTO page = importedPage();
        assertThat(page.getUnknownFields() == null || !page.getUnknownFields().containsKey("linkageRules")).isTrue();
        assertThat(mapper.valueToTree(page).get("linkageRules")).isEqualTo(mapper.valueToTree(rules));
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsImportedRulesAlongsideUnrelatedExtensionProperties() throws Exception {
        PageSchemaDTO page = importedPage();
        page.setExtension(Map.of("recordSource", Map.of("endpoint", "/api/locations/{recordPid}")));
        PluginResourceImporterImpl importer = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
        Map<String, Object> extension = ReflectionTestUtils.invokeMethod(importer, "mergePageExtension", page);
        assertThat(extension).containsEntry("linkageRules", rules)
                .containsEntry("recordSource", Map.of("endpoint", "/api/locations/{recordPid}"));
    }

    @Test
    void projectsPersistedRulesToTheExactRuntimeProperty() {
        com.auraboot.framework.meta.dto.PageSchemaDTO page =
                new com.auraboot.framework.meta.dto.PageSchemaDTO();
        page.setExtension(Map.of("linkageRules", rules));
        PageSchemaConverter converter = mock(PageSchemaConverter.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.invokeMethod(converter, "exposeExtensionTopLevelFields", page);
        assertThat(mapper.valueToTree(page).get("linkageRules")).isEqualTo(mapper.valueToTree(rules));
    }

    @Test
    void stillReportsUnrecognizedPageFields() throws Exception {
        PageSchemaDTO page = mapper.readValue(
                "{\"pageKey\":\"location_form\",\"inventedRules\":[]}", PageSchemaDTO.class);
        assertThat(page.getUnknownFields()).containsKey("inventedRules");
    }
}
