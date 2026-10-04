package com.auraboot.framework.meta.handler;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.converter.PageSchemaConverter;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSchemaCopyContentTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Stream<Arguments> contracts() throws Exception {
        try (var input = PageSchemaCopyContentTest.class.getResourceAsStream("/meta/page-copy-format-contract.json")) {
            return StreamSupport.stream(JSON.readTree(input).spliterator(), false)
                    .map(row -> Arguments.of(row.path("name").asText(), row));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void sharedFrontendContractSurvivesEntityDtoRoundTrip(String name, JsonNode fixture) throws Exception {
        PageSchemaDTO source = JSON.treeToValue(fixture.path("source"), PageSchemaDTO.class);
        JsonNode original = JSON.valueToTree(source);
        PageSchemaCreateRequest request = new PageSchemaCreateRequest();
        PageSchemaCopyContent.apply(source, request);
        assertContent(request, fixture.path("expected"));
        assertThat(JSON.<JsonNode>valueToTree(source)).isEqualTo(original);

        PageSchemaConverter converter = Mappers.getMapper(PageSchemaConverter.class);
        ReflectionTestUtils.setField(converter, "objectMapper", JSON);
        ReflectionTestUtils.setField(converter, "extensionConverter", new ExtensionConverter());
        ReflectionTestUtils.setField(converter, "utcDateTimeMapper", new UtcDateTimeMapper());
        PageSchemaDTO restored = converter.toDTO(converter.toEntity(request));
        assertThat(restored.getSchemaVersion()).isEqualTo(4);
        assertThat(JSON.<JsonNode>valueToTree(restored.getBlocks())).isEqualTo(fixture.path("expected").path("blocks"));
        assertThat(restored.getDataSources()).isEqualTo(request.getDataSources());
        // The converter stores the registry in extension and exposes it again on the DTO.
        assertThat(restored.getExtension()).containsAllEntriesOf(request.getExtension());
    }

    private void assertContent(PageSchemaCreateRequest request, JsonNode expected) {
        JsonNode actual = JSON.valueToTree(request);
        expected.fields().forEachRemaining(field ->
                assertThat(actual.path(field.getKey())).as(field.getKey()).isEqualTo(field.getValue()));
    }

    @Test
    void misplacedSectionContainerCannotSilentlyLoseItsSubtree() {
        PageSchemaDTO source = tree(List.of(Map.of("id", "section", "blockType", "form-section", "blocks",
                List.of(Map.of("id", "nested", "blockType", "table", "blocks", List.of())))));
        assertThatThrownBy(() -> PageSchemaCopyContent.apply(source, new PageSchemaCreateRequest()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("nested");
    }

    @Test
    void unknownTableChildCannotBecomeAnInvalidColumn() {
        PageSchemaDTO source = tree(List.of(Map.of("id", "table", "blockType", "table", "blocks",
                List.of(Map.of("id", "nested", "blockType", "rich-text")))));
        assertThatThrownBy(() -> PageSchemaCopyContent.apply(source, new PageSchemaCreateRequest()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("nested");
    }

    @Test
    void unsupportedWidgetFailsWithoutChangingSource() {
        PageSchemaDTO source = tree(List.of(Map.of("id", "bad", "blockType", "widget", "widgetType", "heat-map")));
        JsonNode original = JSON.valueToTree(source);
        assertThatThrownBy(() -> PageSchemaCopyContent.apply(source, new PageSchemaCreateRequest()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("bad");
        assertThat(JSON.<JsonNode>valueToTree(source)).isEqualTo(original);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"action-bar,field", "tabs,table"})
    void misplacedContainerChildrenFail(String container, String childType) {
        PageSchemaDTO source = tree(List.of(Map.of("id", "parent", "blockType", container, "blocks",
                List.of(Map.of("id", "misplaced", "blockType", childType, "field", "name")))));
        assertThatThrownBy(() -> PageSchemaCopyContent.apply(source, new PageSchemaCreateRequest()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("misplaced");
    }

    private PageSchemaDTO tree(List<Object> blocks) {
        PageSchemaDTO source = new PageSchemaDTO();
        source.setKind("list");
        source.setSchemaVersion(3);
        source.setBlocks(blocks);
        return source;
    }

    @Test
    void versionTwoFlatContentKeepsItsRuntimeSemanticsAtVersionFour() {
        PageSchemaDTO source = new PageSchemaDTO();
        source.setSchemaVersion(2);
        source.setKind("list");
        source.setBlocks(List.of(Map.of("id", "table", "blockType", "table", "columns", List.of("name"))));
        source.setDataSources(Map.of("orders", Map.of("model", "order")));
        PageSchemaCreateRequest request = new PageSchemaCreateRequest();
        PageSchemaCopyContent.apply(source, request);
        assertThat(request.getSchemaVersion()).isEqualTo(4);
        assertThat(request.getBlocks()).isEqualTo(source.getBlocks());
        assertThat(request.getDataSources()).isEqualTo(source.getDataSources());
    }
}
