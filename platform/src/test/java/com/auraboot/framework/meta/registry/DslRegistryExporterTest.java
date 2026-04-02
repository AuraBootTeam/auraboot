package com.auraboot.framework.meta.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DslRegistryExporterTest {

    private DslRegistryExporter exporter;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        exporter = new DslRegistryExporter(
                objectMapper,
                new CommandHandlerRegistry(),
                new SideEffectHandlerRegistry(),
                new AutomationActionRegistry(),
                new ExpressionFunctionRegistry(),
                new RenderComponentRegistry(),
                new BlockRendererRegistry(),
                new ChartTypeRegistry()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void export_shouldContainAllEnumKeys() {
        Map<String, Object> result = exporter.export();

        assertThat(result).containsKeys("version", "exportedAt", "enums", "extensions", "mappings");
        assertThat(result.get("version")).isEqualTo("2.0");

        Map<String, Object> enums = (Map<String, Object>) result.get("enums");

        // All 27 DslRegistry enums must be present
        Set<String> expectedKeys = Set.of(
                "ModelType", "DataType", "FieldType", "RelationType", "FieldSemanticRole",
                "CommandType", "AutoSetStrategy", "PreconditionOperator", "AggregateFunction",
                "RiskLevel", "LinkageActionType",
                "PageKind", "BlockType", "PageSuffix", "SavedViewType", "SavedViewScope", "ChartType",
                "AutomationTrigger", "NotificationChannel",
                "DataPermissionScope", "DataPermissionMask",
                "NamedQueryStatus", "AutomationLogStatus", "BpmTriggerType", "BpmNodeIntervention",
                "ChartDataSourceType", "PluginResourceType"
        );
        assertThat(enums.keySet()).containsExactlyInAnyOrderElementsOf(expectedKeys);
    }

    @Test
    void exportAsJson_shouldProduceValidJson() throws Exception {
        String json = exporter.exportAsJson();

        assertThat(json).isNotBlank();

        // Verify it's parseable JSON
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() {});
        assertThat(parsed).containsKey("version");
        assertThat(parsed).containsKey("enums");
        assertThat(parsed).containsKey("extensions");
        assertThat(parsed).containsKey("mappings");
    }
}
