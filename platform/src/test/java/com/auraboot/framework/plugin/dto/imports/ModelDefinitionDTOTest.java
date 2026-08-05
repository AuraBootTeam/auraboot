package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDefinitionDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void immutableIsAFirstClassModelContract() throws Exception {
        ModelDefinitionDTO model = objectMapper.readValue(
                "{\"code\":\"qdp_revision\",\"immutable\":true}",
                ModelDefinitionDTO.class);

        assertThat(model.getImmutable()).isTrue();
        assertThat(model.getUnknownFields()).isNull();
    }

    @Test
    void immutableDefaultsToFalseForExistingPlugins() throws Exception {
        ModelDefinitionDTO model = objectMapper.readValue(
                "{\"code\":\"mutable_master\"}",
                ModelDefinitionDTO.class);

        assertThat(Boolean.TRUE.equals(model.getImmutable())).isFalse();
    }
}
