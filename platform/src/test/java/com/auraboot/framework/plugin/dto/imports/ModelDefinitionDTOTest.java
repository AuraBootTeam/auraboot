package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDefinitionDTOTest {

    @Test
    void explicitEmptyWriterListsSurviveImportAndSerialization() throws Exception {
        String json = """
                {"code":"audit_record","extension":{"commandWriters":{
                  "create":["audit:create"],"update":[],"delete":[]}}}
                """;
        ModelDefinitionDTO model = objectMapper.readValue(json, ModelDefinitionDTO.class);
        var serialized = objectMapper.readTree(objectMapper.writeValueAsString(model));
        assertThat(serialized.path("extension").path("commandWriters"))
                .isEqualTo(objectMapper.readTree(json).path("extension").path("commandWriters"));
        assertThat(model.getUnknownFields()).isNull();
    }

    @Test
    void commandDeletionPolicySurvivesTheExistingExtensionContract() throws Exception {
        ModelDefinitionDTO model = objectMapper.readValue(
                "{\"code\":\"oi_disclosure_package\",\"extension\":{\"commandOnlyDelete\":true}}",
                ModelDefinitionDTO.class);
        assertThat(model.getExtension()).containsEntry("commandOnlyDelete", true);
        assertThat(model.getUnknownFields()).isNull();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void immutableIsAFirstClassModelContract() throws Exception {
        ModelDefinitionDTO model = objectMapper.readValue(
                "{\"code\":\"qdp_revision\",\"immutable\":true,\"commandOnlyCreate\":true}",
                ModelDefinitionDTO.class);

        assertThat(model.getImmutable()).isTrue();
        assertThat(model.getCommandOnlyCreate()).isTrue();
        assertThat(model.getUnknownFields()).isNull();
    }

    @Test
    void immutableDefaultsToFalseForExistingPlugins() throws Exception {
        ModelDefinitionDTO model = objectMapper.readValue(
                "{\"code\":\"mutable_master\"}",
                ModelDefinitionDTO.class);

        assertThat(Boolean.TRUE.equals(model.getImmutable())).isFalse();
        assertThat(Boolean.TRUE.equals(model.getCommandOnlyCreate())).isFalse();
    }
}
