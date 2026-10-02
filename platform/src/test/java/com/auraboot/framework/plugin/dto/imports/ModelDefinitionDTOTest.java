package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDefinitionDTOTest {

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
